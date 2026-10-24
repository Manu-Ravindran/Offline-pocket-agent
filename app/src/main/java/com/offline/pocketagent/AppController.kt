package com.offline.pocketagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.offline.pocketagent.documents.DocumentExtractor
import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import com.offline.pocketagent.documents.ImageDecodeLimits
import com.offline.pocketagent.inference.ChatMessage
import com.offline.pocketagent.inference.LocalInference
import com.offline.pocketagent.security.TrustedModelPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** Imports are copied to private storage and processed locally. The original files are never modified. */
class AppController(context: Context, private val onStatus: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private val documentsRoot = File(app.filesDir, "documents").apply { mkdirs() }
    private val modelsRoot = File(app.filesDir, "models").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("pocket_agent", Context.MODE_PRIVATE)
    private val inference = LocalInference(app)
    private val mutex = Mutex()
    private val conversation = mutableListOf<ConversationTurn>()
    @Volatile private var activeOperation: Job? = null

    suspend fun importDocument(uri: Uri): ImportedDocument = mutex.withLock {
        withActiveOperation {
            require((documentsRoot.listFiles()?.count { File(it, "document.json").isFile } ?: 0) < MAX_DOCUMENTS) {
                "This first version stores up to $MAX_DOCUMENTS documents. Clear app storage to start a new library."
            }
            val name = displayName(uri)
            onStatus("Importing $name…")
            val id = UUID.randomUUID().toString().substring(0, 8)
            val directory = File(documentsRoot, id)
            check(directory.mkdir()) { "A document folder could not be created. Please try importing again." }
            try {
                val copy = copyFromUri(uri, File(directory, "original"), MAX_DOCUMENT_BYTES)
                val extracted = DocumentExtractor(context = app).extract(copy, name, app.contentResolver.getType(uri))
                val entry = LibraryDocument(id, extracted)
                writeDocument(File(directory, "document.json"), entry)
                entry.toImported()
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun listDocuments(): List<ImportedDocument> = mutex.withLock {
        withActiveOperation { readLibrary().map { it.toImported() } }
    }

    suspend fun previewDocument(id: String): String = mutex.withLock {
        withActiveOperation {
            val entry = requireNotNull(readLibrary().firstOrNull { it.id == id }) {
                "This document could not be reopened. Import it again."
            }
            buildString {
                append(entry.document.name).append("\n\n")
                entry.document.warnings.forEach { append(it).append("\n\n") }
                if (entry.document.sections.isEmpty()) append("No text was extracted from this file.")
                for ((index, section) in entry.document.sections.withIndex()) {
                    append("[").append(entry.id).append(":").append(index).append("] ")
                    append(section.locator).append("\n").append(section.text).append("\n\n")
                    if (length >= 40_000) {
                        append("Preview stopped at 40,000 characters. The index may contain additional text.")
                        break
                    }
                }
            }
        }
    }

    suspend fun configureModel(modelUri: Uri, projectorUri: Uri? = null): String = mutex.withLock {
        withActiveOperation {
            val directory = File(modelsRoot, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val name = displayName(modelUri)
                onStatus("Copying $name to private storage. Large models can take a while…")
                var modelDigest: String? = null
                val model = copyFromUri(modelUri, File(directory, "model.gguf"), MAX_MODEL_BYTES) { modelDigest = it }
                requireGguf(model)
                TrustedModelPolicy(app).verify(model, TrustedModelPolicy.Role.MODEL, modelDigest)
                val projector = projectorUri?.let {
                    onStatus("Copying the matching vision file…")
                    var projectorDigest: String? = null
                    copyFromUri(it, File(directory, "mmproj-model.gguf"), MAX_PROJECTOR_BYTES) { projectorDigest = it }.also { file ->
                        requireGguf(file)
                        TrustedModelPolicy(app).verify(file, TrustedModelPolicy.Role.PROJECTOR, projectorDigest)
                    }
                }
                onStatus("Loading the model on this phone…")
                inference.loadModel(model, projector)
                preferences.edit().putString("model_path", model.absolutePath)
                    .putString("model_name", name)
                    .putString("projector_path", projector?.absolutePath).apply()
                modelsRoot.listFiles()?.filter { it != directory }?.forEach { it.deleteRecursively() }
                "Loaded $name${if (inference.isVisionEnabled) " · vision enabled" else " · text and OCR"}"
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }
    }

    fun hasSavedModel(): Boolean = preferences.getString("model_path", null)?.let(::File)?.isFile == true

    suspend fun warmUpSavedModel(): Boolean = mutex.withLock {
        withActiveOperation {
            if (inference.isLoaded) true else {
                val model = preferences.getString("model_path", null)?.let(::File)?.takeIf { it.isFile }
                if (model == null) false else {
                    val projector = preferences.getString("projector_path", null)?.let(::File)?.takeIf { it.isFile }
                    onStatus("Loading your saved local model…")
                    inference.loadModel(model, projector)
                    onStatus("Ready. ${modelSummary()}")
                    true
                }
            }
        }
    }

    suspend fun ask(
        question: String,
        documentIds: List<String>? = null,
        onPartial: (String) -> Unit = {},
    ): AgentAnswer = mutex.withLock {
        withActiveOperation {
            require(question.isNotBlank()) { "Enter a question first." }
            require(question.length <= 2_000) { "Please keep your question under 2,000 characters." }
            if (!inference.isLoaded) {
                val model = preferences.getString("model_path", null)?.let(::File)
                require(model?.isFile == true) { "Choose and load a local GGUF model first. Document import and OCR already work without one." }
                val projector = preferences.getString("projector_path", null)?.let(::File)?.takeIf { it.isFile }
                onStatus("Loading your saved local model…")
                inference.loadModel(model, projector)
            }
            val library = readLibrary().let { all ->
                if (documentIds.isNullOrEmpty()) all else {
                    require(documentIds.all { id -> all.any { it.id == id } }) { "An attached document could not be reopened. Import it again." }
                    all.filter { it.id in documentIds }
                }
            }
            val engine = AgentEngine(
                generate = { prompt, image ->
                    runInference(prompt, image, maxTokens = 256)
                },
                onStatus = onStatus,
                streamGenerate = { prompt, image, maximum, onDelta ->
                    runInference(prompt, image, maximum, onDelta)
                },
            )
            val result = engine.respond(question.trim(), library, inference.isVisionEnabled, onPartial, conversation.takeLast(6))
            conversation += ConversationTurn(question.take(1_000), result.text.take(2_000))
            while (conversation.size > 6) conversation.removeAt(0)
            result
        }
    }

    fun modelSummary(): String {
        val name = preferences.getString("model_name", null)
        return when {
            inference.isLoaded -> "${name ?: "Local model"} is loaded${if (inference.isVisionEnabled) " with vision" else " for text and OCR"}."
            name != null && hasSavedModel() -> "$name is saved and loads automatically when the app opens."
            else -> "No model loaded. Choose a GGUF model to enable answers."
        }
    }

    fun cancelGeneration() {
        activeOperation?.cancel(CancellationException("Cancelled by the user."))
    }

    fun clearConversation() {
        check(activeOperation == null) { "Wait for the current operation to finish before starting a new conversation." }
        conversation.clear()
    }

    fun close() {
        cancelGeneration()
        inference.close()
    }

    private suspend fun <T> withActiveOperation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        activeOperation = job
        try {
            block()
        } finally {
            if (activeOperation === job) activeOperation = null
        }
    }

    private suspend fun runInference(
        prompt: String,
        image: File?,
        maxTokens: Int,
        onDelta: ((String) -> Unit)? = null,
    ): String {
        val prepared = image?.let(::prepareVisionImage)
        return try {
            inference.generate(listOf(ChatMessage("user", prompt)), prepared, onDelta, maxTokens)
        } finally {
            prepared?.delete()
        }
    }

    private fun prepareVisionImage(source: File): File {
        val header = source.inputStream().use { input ->
            ByteArray(64).let { buffer -> buffer.copyOf(input.read(buffer).coerceAtLeast(0)) }
        }
        require(ImageDecodeLimits.supportsSignature(header)) { "Use a JPEG, PNG, WebP, or HEIF camera image." }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, dimensions)
        val sample = ImageDecodeLimits.sampleSize(dimensions.outWidth, dimensions.outHeight, 1_536)
        val decoded = requireNotNull(BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "This image cannot be decoded for vision." }
        try {
            ImageDecodeLimits.validateDecoded(decoded.width, decoded.height, decoded.allocationByteCount.toLong(), 1_536)
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val bitmap = if (matrix.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        val target = File.createTempFile("vision-", ".jpg", app.cacheDir)
        try {
            target.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) }
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun copyFromUri(uri: Uri, destination: File, maximum: Long, onDigest: ((String) -> Unit)? = null): File {
        val knownSize = app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
        return requireNotNull(app.contentResolver.openInputStream(uri)) { "The selected file could not be opened." }.use { input ->
            val receipt = ImportCopier.copy(input, destination, maximum, knownSize, hash = onDigest != null)
            receipt.sha256?.let { onDigest?.invoke(it) }
            receipt.file
        }
    }

    private fun requireGguf(file: File) {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        require(read == 4 && header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
            "Choose a GGUF model file, not a ZIP, safetensors file, or download webpage."
        }
    }

    private fun displayName(uri: Uri): String = app.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.take(200)?.ifBlank { null } ?: "Imported file"

    private fun readLibrary(): List<LibraryDocument> = documentsRoot.listFiles().orEmpty()
        .filter { File(it, "document.json").isFile }.sortedBy { it.lastModified() }.take(MAX_DOCUMENTS)
        .mapNotNull { directory ->
            runCatching {
                val metadata = File(directory, "document.json")
                require(metadata.length() <= MAX_METADATA_BYTES)
                val data = JSONObject(metadata.readText())
                val sections = data.getJSONArray("sections")
                val warnings = data.getJSONArray("warnings")
                LibraryDocument(data.getString("id"), ExtractedDocument(
                    name = data.getString("name"), kind = data.getString("kind"),
                    sections = (0 until sections.length()).map { index ->
                        val section = sections.getJSONObject(index)
                        DocumentSection(
                            section.getString("locator"), section.getString("text"), section.getString("kind"),
                            if (section.isNull("numericValue")) null else section.optString("numericValue"),
                            section.optBoolean("isFormula", false),
                        )
                    },
                    warnings = (0 until warnings.length()).map { warnings.getString(it) },
                    imagePath = if (data.optBoolean("hasImage")) File(directory, "original").absolutePath else null,
                ))
            }.getOrElse {
                onStatus("A saved document could not be reopened. Import that document again.")
                null
            }
        }

    private fun writeDocument(destination: File, entry: LibraryDocument) {
        val document = entry.document
        val sections = JSONArray()
        for (section in document.sections) sections.put(JSONObject().put("locator", section.locator)
            .put("text", section.text).put("kind", section.kind)
            .put("numericValue", section.numericValue ?: JSONObject.NULL).put("isFormula", section.isFormula))
        val data = JSONObject().put("id", entry.id).put("name", document.name).put("kind", document.kind)
            .put("sections", sections).put("warnings", JSONArray(document.warnings))
            .put("hasImage", document.imagePath != null)
        val pending = File(destination.parentFile, destination.name + ".part")
        pending.writeText(data.toString())
        check(pending.renameTo(destination)) { "The document index could not be saved." }
    }

    private fun LibraryDocument.toImported(): ImportedDocument = ImportedDocument(
        id, document.name,
        "${document.kind.uppercase()} · ${document.sections.size} sections" +
            document.warnings.firstOrNull()?.let { "\n$it" }.orEmpty(),
    )

    companion object {
        private const val MAX_DOCUMENTS = 30
        private const val MAX_DOCUMENT_BYTES = 50L * 1024 * 1024
        private const val MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_PROJECTOR_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 3L * 1024 * 1024
    }
}
