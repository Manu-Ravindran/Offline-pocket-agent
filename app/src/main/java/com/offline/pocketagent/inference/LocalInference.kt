package com.offline.pocketagent.inference

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.offline.pocketagent.security.TrustedModelPolicy
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.geniex.sdk.bean.ChatMessage as SdkChatMessage

/** A message passed to the local model; tool results can be expressed as user messages. */
data class ChatMessage(val role: String, val content: String)

/**
 * Actual on-device GGUF inference through the pinned Qualcomm GenieX 0.9.3 AAR.
 * Only caller-supplied files are opened. This class never pulls/downloads models.
 * CPU execution is explicit, so a model does not silently require an NPU-specific bundle.
 */
class LocalInference(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var llm: LlmWrapper? = null
    @Volatile private var vlm: VlmWrapper? = null
    @Volatile private var closed = false

    val isLoaded: Boolean get() = !closed && (llm != null || vlm != null)
    val isVisionEnabled: Boolean get() = !closed && vlm != null

    suspend fun loadModel(modelFile: File, projectorFile: File? = null) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "The inference session is closed." }
                requireGguf(modelFile, "Model")
                projectorFile?.let { requireGguf(it, "Vision projector") }
                val canonicalModel = modelFile.canonicalFile
                val canonicalProjector = projectorFile?.canonicalFile
                require(canonicalProjector == null || canonicalProjector.parentFile == canonicalModel.parentFile) {
                    "The model and vision projector must be imported together into the same private model folder."
                }
                val trust = TrustedModelPolicy(appContext)
                trust.verify(modelFile, TrustedModelPolicy.Role.MODEL)
                projectorFile?.let { trust.verify(it, TrustedModelPolicy.Role.PROJECTOR) }
                destroyModels()
                initializeSdk()
                val config = ModelConfig(
                    nCtx = 8192,
                    nThreads = 4,
                    nThreadsBatch = 4,
                    // Both text and vision prefill support batched decode in GenieX
                    // 0.9.3. A batch of one needlessly serializes long document prompts.
                    nBatch = 512,
                    nUBatch = 128,
                    nGpuLayers = 0,
                )
                if (projectorFile == null) {
                    llm = LlmWrapper.builder().llmCreateInput(
                        LlmCreateInput(
                            model_path = canonicalModel.path,
                            config = config,
                            runtime_id = GenieXSdk.PLUGIN_ID_LLAMA_CPP,
                            compute_unit = ComputeUnitValue.CPU.value,
                        ),
                    ).build().getOrThrow()
                } else {
                    vlm = VlmWrapper.builder().vlmCreateInput(
                        VlmCreateInput(
                            model_path = canonicalModel.path,
                            mmproj_path = canonicalProjector?.path,
                            config = config,
                            runtime_id = GenieXSdk.PLUGIN_ID_LLAMA_CPP,
                            compute_unit = ComputeUnitValue.CPU.value,
                            vit_device_id = "CPU",
                        ),
                    ).build().getOrThrow()
                }
            }
        }

    /**
     * [onToken] receives decoded text deltas on an IO worker. The SDK buffers
     * incomplete UTF-8 sequences before emitting them; concatenate the strings
     * directly rather than decoding or splitting them again.
     */
    suspend fun generate(
        messages: List<ChatMessage>,
        imageFile: File? = null,
        onToken: ((String) -> Unit)? = null,
        maxTokens: Int = 512,
    ): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(isLoaded) { "Import and load a GGUF model first." }
                require(messages.isNotEmpty()) { "A prompt is required." }
                require(maxTokens in 1..2048) { "The output limit must be between 1 and 2048 tokens." }
                require(messages.all { it.role in setOf("system", "user", "assistant") }) {
                    "Messages must use system, user, or assistant roles."
                }
                imageFile?.let {
                    require(it.isFile && it.canRead()) { "The attached image is unavailable." }
                    check(vlm != null) { "Load a matching vision model and projector to analyze images." }
                }
                // The external scope lets cancellation call stopStream while native generate
                // occupies a worker thread. Await termination before releasing the model lock.
                val callerContext = currentCoroutineContext()
                val generation = nativeScope.async {
                    generateLocked(messages, imageFile, maxTokens) { delta ->
                        if (callerContext.isActive) onToken?.invoke(delta)
                    }
                }
                try {
                    generation.await()
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        llm?.stopStream()
                        vlm?.stopStream()
                        generation.cancelAndJoin()
                    }
                    throw cancelled
                }
            }
        }

    private suspend fun generateLocked(
        messages: List<ChatMessage>,
        imageFile: File?,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): String {
        val config = GenerationConfig(
            maxTokens = maxTokens,
            samplerConfig = SamplerConfig(temperature = 0.2f, topP = 0.8f, topK = 20),
        )
        val vision = vlm
        if (vision != null) {
            // The VLM backend requires append-only history including its exact last
            // generation. Agent planner requests are independent prompts, so reset
            // this context while retaining the loaded model and vision encoder.
            check(vision.reset() == 0) { "Could not reset the vision model." }
            val lastUser = messages.indexOfLast { it.role == "user" }
            require(imageFile == null || lastUser >= 0) { "An image needs a user message." }
            val formattedMessages = messages.mapIndexed { index, message ->
                val contents = mutableListOf<VlmContent>()
                if (imageFile != null && index == lastUser) {
                    contents += VlmContent("image", imageFile.absolutePath)
                }
                contents += VlmContent("text", message.content)
                VlmChatMessage(message.role, contents)
            }.toTypedArray()
            val prompt = vision.applyChatTemplate(
                messages = formattedMessages,
                tools = null,
                enableThinking = false,
            )
                .getOrThrow().formattedText
            val mediaConfig = vision.injectMediaPathsToConfig(formattedMessages, config)
            return collectOutput(vision.generateStreamFlow(prompt, mediaConfig), onToken)
        }
        val text = checkNotNull(llm) { "No local model is loaded." }
        // The text backend safely reuses common token prefixes and rolls back the
        // differing suffix itself. Clearing it here would discard useful prefill.
        val prompt = text.applyChatTemplate(
            messages = messages.map { SdkChatMessage(it.role, it.content) }.toTypedArray(),
            tools = null,
            enableThinking = false,
            addGenerationPrompt = true,
        ).getOrThrow().formattedText
        return collectOutput(text.generateStreamFlow(prompt = prompt, config = config), onToken)
    }

    private suspend fun collectOutput(
        flow: Flow<LlmStreamResult>,
        onToken: (String) -> Unit,
    ): String {
        val answer = StringBuilder()
        var completed = false
        // The SDK's callback uses trySend; an unbounded, fused channel avoids dropping
        // tokens if Android briefly pauses the consumer while native inference runs.
        flow.buffer(Channel.UNLIMITED).collect { event ->
            when (event) {
                is LlmStreamResult.Token -> {
                    answer.append(event.text)
                    if (event.text.isNotEmpty()) onToken(event.text)
                }
                is LlmStreamResult.Completed -> completed = true
                is LlmStreamResult.Error -> throw event.throwable
            }
        }
        check(completed) { "The model stopped without completing a response." }
        return answer.toString().trim().also {
            check(it.isNotEmpty()) { "The model returned an empty response. Check the model and projector compatibility." }
        }
    }

    private fun initializeSdk() {
        var failure: String? = null
        try {
            GenieXSdk.getInstance().init(appContext, object : GenieXSdk.InitCallback {
                override fun onSuccess() = Unit
                override fun onFailure(reason: String) { failure = reason }
            })
        } catch (error: LinkageError) {
            throw IllegalStateException("The local inference libraries could not load on this device.", error)
        }
        check(failure == null) { "Local inference initialization failed: $failure" }
    }

    private fun requireGguf(file: File, label: String) {
        require(file.isFile && file.canRead()) { "$label file is unavailable." }
        val magic = ByteArray(4)
        val count = file.inputStream().use { it.read(magic) }
        require(count == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))) {
            "$label must be a GGUF file. ZIP/QAIRT bundles are not supported by this import flow."
        }
    }

    private fun destroyModels() {
        llm?.close()
        llm = null
        vlm?.close()
        vlm = null
    }

    override fun close() {
        if (closed) return
        closed = true
        nativeScope.launch {
            llm?.stopStream()
            vlm?.stopStream()
            mutex.withLock { destroyModels() }
            nativeScope.cancel()
        }
    }
}
