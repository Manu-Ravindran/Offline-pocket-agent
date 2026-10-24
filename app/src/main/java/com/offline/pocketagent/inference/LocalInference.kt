package com.offline.pocketagent.inference

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LLMTokenCallback
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmGenerateResult
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.offline.pocketagent.diagnostics.AppDiagnostics
import com.offline.pocketagent.security.TrustedModelPolicy
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.callbackFlow
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
    // The outer mutex protects long operations. This short lifetime guard only
    // serializes publication, native stop, and destruction. Never wait for the
    // outer mutex while holding this guard: stop must remain callable mid-generate.
    private val nativeLifetimeMutex = Mutex()
    private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextInferenceId = AtomicLong(0)
    @Volatile private var llm: LlmWrapper? = null
    @Volatile private var vlm: NativeVisionSession? = null
    private var loadedAttempt: Any? = null
    @Volatile private var closed = false

    val isLoaded: Boolean get() = !closed && (llm != null || vlm != null)
    val isVisionEnabled: Boolean get() = !closed && vlm?.supportsVision == true
    val visionUnavailableReason: String? get() = if (!closed && vlm?.supportsVision == false) {
        "The text model is ready, but GenieX could not activate its vision projector. " +
            "Image analysis is unavailable; check the model setup and retry loading."
    } else null

    suspend fun loadModel(modelFile: File, projectorFile: File? = null) {
        val attempt = Any()
        try {
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
                currentCoroutineContext().ensureActive()
                check(!closed) { "The inference session is closed." }
                AppDiagnostics.event("native_model_load_start", mapOf(
                    "projector_requested" to (projectorFile != null).toString(),
                ))
                destroyModels()
                loadedAttempt = attempt
                inferenceStage("SDK initialization") { initializeSdk() }
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
                // SDK builders create a native handle before a dispatcher return can
                // deliver cancellation. Publish ownership while non-cancellable so
                // the outer cleanup can always release that handle before import
                // failure cleanup removes the model directory.
                withContext(NonCancellable) {
                    if (projectorFile == null) {
                        val created = inferenceStage("Text model initialization") {
                            LlmWrapper.builder().llmCreateInput(
                                LlmCreateInput(
                                    model_path = canonicalModel.path,
                                    config = config,
                                    runtime_id = GenieXSdk.PLUGIN_ID_LLAMA_CPP,
                                    compute_unit = ComputeUnitValue.CPU.value,
                                ),
                            ).build().getOrThrow()
                        }
                        nativeLifetimeMutex.withLock { llm = created }
                        inferenceStage("Text chat template initialization") {
                            checkNotNull(llm).applyChatTemplate(
                                arrayOf(SdkChatMessage("user", "Hello")), null, false, true,
                            ).getOrThrow()
                        }
                    } else {
                        val created = inferenceStage("Multimodal model initialization") {
                            NativeVisionSession(
                                VlmCreateInput(
                                    model_path = canonicalModel.path,
                                    mmproj_path = canonicalProjector?.path,
                                    config = config,
                                    runtime_id = GenieXSdk.PLUGIN_ID_LLAMA_CPP,
                                    compute_unit = ComputeUnitValue.CPU.value,
                                    vit_device_id = "CPU",
                                ),
                            )
                        }
                        nativeLifetimeMutex.withLock { vlm = created }
                        inferenceStage("Multimodal chat template initialization") {
                            checkNotNull(vlm).applyChatTemplate(arrayOf(
                                VlmChatMessage("user", listOf(VlmContent("text", "Hello"))),
                            )).formattedText.also { check(it.isNotBlank()) { "The model chat template was empty." } }
                        }
                    }
                }
                check(!closed) { "The inference session was closed while loading." }
                AppDiagnostics.event("native_vision_capability", mapOf(
                    "projector_requested" to (projectorFile != null).toString(),
                    "vision_ready" to isVisionEnabled.toString(),
                ))
                AppDiagnostics.event("native_model_load_complete", mapOf(
                    "text_ready" to isLoaded.toString(),
                    "vision_ready" to isVisionEnabled.toString(),
                ))
              }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    if (loadedAttempt === attempt) destroyModels()
                }
            }
            throw error
        }
    }

    /** Clears old conversation state without unloading the verified model weights. */
    suspend fun resetConversationContext() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "The inference session is closed." }
            llm?.let { text -> inferenceStage("Text conversation reset") {
                check(text.reset() == 0) { "The runtime could not clear the old text context." }
            } }
            vlm?.let { vision -> inferenceStage("Multimodal conversation reset") {
                check(vision.reset() == 0) { "The runtime could not clear the old image context." }
            } }
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
                    check(isVisionEnabled) {
                        visionUnavailableReason ?: "Load the model with its matching vision projector to analyze images."
                    }
                }
                // The external scope lets cancellation call stopStream while native generate
                // occupies a worker thread. Await termination before releasing the model lock.
                val callerContext = currentCoroutineContext()
                // This boundary excludes controller routing/model loading and time
                // waiting for the inference mutex. It includes worker scheduling,
                // reset/template work, prefill/encoding, decoding, and stop cleanup.
                val measurements = InferenceMeasurements(nextInferenceId.incrementAndGet(), imageFile != null)
                val generation = nativeScope.async {
                    generateLocked(messages, imageFile, maxTokens, measurements) { delta ->
                        if (callerContext.isActive) onToken?.invoke(delta)
                    }
                }
                try {
                    generation.await().also {
                        AppDiagnostics.event("inference_complete", measurements.fields())
                    }
                } catch (cancelled: CancellationException) {
                    try {
                        withContext(NonCancellable) {
                            requestStopStreams()
                            generation.cancelAndJoin()
                            runCatching { llm?.reset() }
                            runCatching { vlm?.reset() }
                        }
                    } finally {
                        AppDiagnostics.event("inference_cancelled", measurements.fields())
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    AppDiagnostics.event("inference_failed", measurements.fields())
                    throw error
                }
            }
        }

    private suspend fun generateLocked(
        messages: List<ChatMessage>,
        imageFile: File?,
        maxTokens: Int,
        measurements: InferenceMeasurements,
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
            inferenceStage("Multimodal context reset") {
                check(vision.reset() == 0) { "Could not reset the vision model." }
            }
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
            val stage = if (imageFile == null) "Text" else "Image"
            val prompt = inferenceStage("$stage chat template") {
                vision.applyChatTemplate(formattedMessages).formattedText.also {
                    check(it.isNotBlank()) { "The model chat template was empty." }
                }
            }
            config.imagePaths = imageFile?.let { arrayOf(it.absolutePath) }
            config.imageCount = if (imageFile == null) 0 else 1
            return inferenceStage("$stage generation") {
                collectOutput(visionStream(vision, prompt, config), measurements, onToken)
            }
        }
        val text = checkNotNull(llm) { "No local model is loaded." }
        // The text backend safely reuses common token prefixes and rolls back the
        // differing suffix itself. Clearing it here would discard useful prefill.
        val prompt = inferenceStage("Text chat template") {
            text.applyChatTemplate(
                messages = messages.map { SdkChatMessage(it.role, it.content) }.toTypedArray(),
                tools = null,
                enableThinking = false,
                addGenerationPrompt = true,
            ).getOrThrow().formattedText
        }
        return inferenceStage("Text generation") {
            collectOutput(text.generateStreamFlow(prompt = prompt, config = config), measurements, onToken)
        }
    }

    private fun visionStream(
        vision: NativeVisionSession,
        prompt: String,
        config: GenerationConfig,
    ): Flow<LlmStreamResult> = callbackFlow {
        val completed = AtomicBoolean(false)
        val callback = object : LLMTokenCallback {
            override fun onToken(token: String): Boolean = isActive && trySend(LlmStreamResult.Token(token)).isSuccess
            override fun onComplete(result: LlmGenerateResult) {
                completed.set(true)
                trySend(LlmStreamResult.Completed(result.profileData))
                close()
            }
        }
        try {
            withContext(Dispatchers.IO) { vision.generate(prompt, config, callback) }
            if (!completed.get()) {
                trySend(LlmStreamResult.Error(IllegalStateException("The native runtime returned without completing generation.")))
                close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            trySend(LlmStreamResult.Error(error))
            close()
        }
        awaitClose { }
    }

    private suspend fun <T> inferenceStage(label: String, action: suspend () -> T): T {
        val phase = diagnosticPhase(label)
        val started = System.nanoTime()
        // The helper flushes this bounded, fixed-name marker before JNI entry.
        // Do not add prompts, outputs, image details, paths, or exception messages.
        AppDiagnostics.event("inference_stage_start", mapOf("phase" to phase))
        try {
            val result = action()
            AppDiagnostics.event("inference_stage_end", mapOf(
                "phase" to phase,
                "duration_ms" to ((System.nanoTime() - started) / 1_000_000L).toString(),
            ))
            return result
        } catch (cancelled: CancellationException) {
            AppDiagnostics.event("inference_stage_end", mapOf("phase" to phase))
            throw cancelled
        } catch (error: Throwable) {
            AppDiagnostics.event("inference_stage_fail", mapOf("phase" to phase))
            AppDiagnostics.failure("inference_stage_fail", error)
            if (error is Exception) {
                throw IllegalStateException("$label failed: ${error.message?.take(300) ?: error.javaClass.simpleName}", error)
            }
            throw error
        }
    }

    /** All logging fields come from this fixed internal allowlist, never model data. */
    private fun diagnosticPhase(label: String): String = when (label) {
        "SDK initialization" -> "sdk_initialization"
        "Text model initialization" -> "text_model_initialization"
        "Multimodal model initialization" -> "multimodal_model_initialization"
        "Text chat template initialization" -> "text_template_initialization"
        "Multimodal chat template initialization" -> "multimodal_template_initialization"
        "Text conversation reset" -> "text_conversation_reset"
        "Multimodal conversation reset" -> "multimodal_conversation_reset"
        "Multimodal context reset" -> "multimodal_context_reset"
        "Text chat template" -> "text_template"
        "Image chat template" -> "image_template"
        "Text generation" -> "text_generation"
        "Image generation" -> "image_generation"
        else -> "model_load"
    }

    private suspend fun collectOutput(
        flow: Flow<LlmStreamResult>,
        measurements: InferenceMeasurements,
        onToken: (String) -> Unit,
    ): String {
        val answer = StringBuilder()
        var completed = false
        // The SDK's callback uses trySend; an unbounded, fused channel avoids dropping
        // tokens if Android briefly pauses the consumer while native inference runs.
        flow.buffer(Channel.UNLIMITED).collect { event ->
            when (event) {
                is LlmStreamResult.Token -> {
                    measurements.observeText(event.text)
                    answer.append(event.text)
                    if (event.text.isNotEmpty()) onToken(event.text)
                }
                is LlmStreamResult.Completed -> {
                    // This is the SDK's actual native counter, never a count of
                    // callback chunks or an estimate from output length.
                    measurements.observeGeneratedTokens(event.profile.generatedTokens)
                    completed = true
                }
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

    private suspend fun requestStopStreams() {
        nativeLifetimeMutex.withLock {
            runCatching { llm?.stopStream() }
            runCatching { vlm?.stopStream() }
        }
    }

    private suspend fun destroyModels() {
        nativeLifetimeMutex.withLock {
            val text = llm
            val vision = vlm
            llm = null
            vlm = null
            loadedAttempt = null
            try {
                text?.close()
            } finally {
                vision?.close()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        nativeScope.launch {
            try {
                requestStopStreams()
                // The lifetime guard has been released before waiting here.
                mutex.withLock { destroyModels() }
            } finally {
                nativeScope.cancel()
            }
        }
    }
}

/** Counts only timing and amounts. No prompt, delta, or answer is retained here. */
internal class InferenceMeasurements(
    private val inferenceId: Long,
    private val visionRequested: Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private val firstTextAt = AtomicLong(Long.MIN_VALUE)
    private val outputChars = AtomicLong(0)
    private val generatedTokens = AtomicLong(-1)

    fun observeText(delta: String) {
        if (delta.isEmpty()) return
        firstTextAt.compareAndSet(Long.MIN_VALUE, nanoTime())
        outputChars.addAndGet(delta.codePointCount(0, delta.length).toLong())
    }

    fun observeGeneratedTokens(count: Long) {
        if (count >= 0) generatedTokens.set(count)
    }

    fun fields(): Map<String, String> {
        val elapsed = (nanoTime() - started).coerceAtLeast(1L)
        val characters = outputChars.get()
        return buildMap {
            put("inference_id", inferenceId.toString())
            put("vision_requested", visionRequested.toString())
            put("duration_ms", (elapsed / 1_000_000L).toString())
            put("output_chars", characters.toString())
            put("chars_per_second", String.format(Locale.ROOT, "%.2f", characters.toDouble() * 1_000_000_000.0 / elapsed))
            val first = firstTextAt.get()
            if (first != Long.MIN_VALUE) put("first_token_ms", ((first - started).coerceAtLeast(0) / 1_000_000L).toString())
            val tokens = generatedTokens.get()
            if (tokens >= 0) {
                put("generated_tokens", tokens.toString())
                put("tokens_per_second", String.format(Locale.ROOT, "%.2f", tokens.toDouble() * 1_000_000_000.0 / elapsed))
            }
        }
    }
}
