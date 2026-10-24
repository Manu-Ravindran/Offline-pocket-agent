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
import com.offline.pocketagent.inference.ChatMessage
import com.offline.pocketagent.inference.LocalInference
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** Imports are copied to private storage and processed locally. The original files are never modified. */
class AppController(context: Context, private val onStatus: (String) -> Unit) {
    private val app = context.applicationContext
    private val documentsRoot = File(app.filesDir, "documents").apply { mkdirs() }
    private val modelsRoot = File(app.filesDir, "models").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("pocket_agent", Context.MODE_PRIVATE)
    private val inference = LocalInference(app)
    private val mutex = Mutex()

    suspend fun importDocument(uri: Uri): ImportedDocument = mutex.withLock {
        withContext(Dispatchers.IO) {
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
                val extracted = DocumentExtractor().extract(copy, name, app.contentResolver.getType(uri))
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
        withContext(Dispatchers.IO) { readLibrary().map { it.toImported() } }
    }

    suspend fun configureModel(modelUri: Uri, projectorUri: Uri? = null): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val directory = File(modelsRoot, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val name = displayName(modelUri)
                onStatus("Copying $name to private storage. Large models can take a while…")
                val model = copyFromUri(modelUri, File(directory, "model.gguf"), MAX_MODEL_BYTES)
                requireGguf(model)
                val projector = projectorUri?.let {
                    onStatus("Copying the matching vision file…")
                    copyFromUri(it, File(directory, "mmproj-model.gguf"), MAX_PROJECTOR_BYTES).also(::requireGguf)
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

    suspend fun ask(question: String): AgentAnswer = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(question.isNotBlank()) { "Enter a question first." }
            if (!inference.isLoaded) {
                val model = preferences.getString("model_path", null)?.let(::File)
                require(model?.isFile == true) { "Choose and load a local GGUF model first. Document import and OCR already work without one." }
                val projector = preferences.getString("projector_path", null)?.let(::File)?.takeIf { it.isFile }
                onStatus("Loading your saved local model…")
                inference.loadModel(model, projector)
            }
            AgentEngine(
                generate = { prompt, image ->
                    val prepared = image?.let(::prepareVisionImage)
                    try {
                        inference.generate(listOf(ChatMessage("user", prompt)), prepared)
                    } finally {
                        prepared?.delete()
                    }
                },
                onStatus = onStatus,
            ).answer(question.trim(), readLibrary(), inference.isVisionEnabled)
        }
    }

    fun modelSummary(): String {
        val name = preferences.getString("model_name", null)
        return when {
            inference.isLoaded -> "${name ?: "Local model"} is loaded${if (inference.isVisionEnabled) " with vision" else " for text and OCR"}."
            name != null -> "$name is saved and will load when you ask a question."
            else -> "No model loaded. Choose a GGUF model to enable answers."
        }
    }

    fun close() = inference.close()

    private fun prepareVisionImage(source: File): File {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, dimensions)
        require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "This image cannot be decoded for vision." }
        var sample = 1
        while (max(dimensions.outWidth, dimensions.outHeight) / sample > 1_536) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "This image cannot be decoded for vision." }
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

    private suspend fun copyFromUri(uri: Uri, destination: File, maximum: Long): File {
        val knownSize = app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
        require(knownSize == null || knownSize <= maximum) { "This file exceeds the ${maximum / (1024 * 1024)} MB import limit." }
        if (knownSize != null) require(destination.parentFile!!.usableSpace > knownSize + 128L * 1024 * 1024) {
            "There is not enough free phone storage to copy this file."
        }
        val pending = File(destination.parentFile, destination.name + ".part")
        try {
            requireNotNull(app.contentResolver.openInputStream(uri)) { "The selected file could not be opened." }.use { input ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= maximum) { "This file exceeds the import size limit." }
                        output.write(buffer, 0, read)
                    }
                    require(copied > 0) { "The selected file is empty." }
                }
            }
            check(pending.renameTo(destination)) { "The imported file could not be saved." }
            return destination
        } finally {
            pending.delete()
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
