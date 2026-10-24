package com.offline.pocketagent.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.offline.pocketagent.security.PdfSandboxClient
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** All document processing stays on-device; the OCR model is bundled in the APK. */
class DocumentExtractor(private val context: Context, private val limits: ExtractionLimits = ExtractionLimits()) {
    suspend fun extract(file: File, displayName: String, mimeType: String? = null): ExtractedDocument =
        withContext(Dispatchers.IO) {
            require(file.isFile) { "The selected file could not be opened." }
            require(file.length() <= limits.maxFileBytes) {
                "This file exceeds the ${limits.maxFileBytes / (1024 * 1024)} MB import limit."
            }
            coroutineContext.ensureActive()
            val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val type = mimeType?.lowercase(Locale.ROOT).orEmpty()
            when {
                extension == "doc" -> throw IllegalArgumentException(
                    "Legacy .doc files are not supported yet. Use Word's Save As to create a .docx copy, then import that copy.",
                )
                extension == "xls" || extension == "xlsb" -> throw IllegalArgumentException(
                    "Legacy .xls and binary .xlsb files are not supported yet. Save an .xlsx copy in Excel, then import that copy.",
                )
                extension == "docx" || type == DOCX_MIME -> OfficeParser(limits).parseDocx(file, displayName)
                extension == "xlsx" || type == XLSX_MIME -> OfficeParser(limits).parseXlsx(file, displayName)
                extension == "pdf" || type == "application/pdf" -> extractPdf(file, displayName)
                extension in IMAGE_EXTENSIONS || type.startsWith("image/") -> extractImage(file, displayName)
                else -> throw IllegalArgumentException("Choose an .xlsx, .docx, PDF, or camera image file.")
            }
        }

    private suspend fun extractPdf(file: File, displayName: String): ExtractedDocument =
        PdfSandboxClient(context).extract(file, displayName, limits)

    private suspend fun extractImage(file: File, displayName: String): ExtractedDocument {
        val bitmap = decodeBoundedImage(file)
        val text = recognizeAndRelease(bitmap)
        val output = SectionCollector(limits.maxTextChars)
        output.add("image OCR", text.trim(), "image_ocr")
        if (text.isBlank()) output.warnings += "No readable text was found in this image."
        output.warnings += "Image import currently extracts printed Latin-script text. General visual understanding requires a vision model."
        return ExtractedDocument(
            displayName, "image", output.sections, output.warnings.distinct(), file.absolutePath,
            extractionComplete = output.extractionComplete && text.isNotBlank(),
        )
    }

    private fun decodeBoundedImage(file: File): Bitmap {
        val header = file.inputStream().use { input ->
            ByteArray(64).let { buffer -> buffer.copyOf(input.read(buffer).coerceAtLeast(0)) }
        }
        require(ImageDecodeLimits.supportsSignature(header)) { "Use a JPEG, PNG, WebP, or HEIF camera image." }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, dimensions)
        val sample = ImageDecodeLimits.sampleSize(dimensions.outWidth, dimensions.outHeight, limits.maxImageEdge)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) { "The selected image could not be decoded." }
        try {
            ImageDecodeLimits.validateDecoded(decoded.width, decoded.height, decoded.allocationByteCount.toLong(), limits.maxImageEdge)
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(-90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90f)
        }
        if (transform.isIdentity) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
    }

    private suspend fun recognizeAndRelease(bitmap: Bitmap): String {
        val recognizer = try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (error: Throwable) {
            bitmap.recycle()
            recognizer.close()
            throw error
        }
        // ML Kit tasks can outlive coroutine cancellation. Release only when the task is done.
        task.addOnCompleteListener(Executor { command -> command.run() }) {
            if (!bitmap.isRecycled) bitmap.recycle()
            recognizer.close()
        }
        return withTimeout(45_000L) { task.await().text }
    }

    companion object {
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}

/** Pure bounds checks are separately testable before invoking Android native decoders. */
internal object ImageDecodeLimits {
    private const val MAX_DIMENSION = 32_768
    private const val MAX_SOURCE_PIXELS = 256_000_000L // Includes Samsung's 200 MP capture mode.

    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        require(width > 0 && height > 0) { "This image format could not be decoded." }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION && width.toLong() * height <= MAX_SOURCE_PIXELS) {
            "This image exceeds the supported source dimensions. Resize it before importing."
        }
        require(maxEdge > 0)
        var sample = 1
        while ((max(width, height).toLong() + sample - 1) / sample > maxEdge) sample *= 2
        return sample
    }

    fun validateDecoded(width: Int, height: Int, allocatedBytes: Long, maxEdge: Int) {
        require(width in 1..maxEdge && height in 1..maxEdge && allocatedBytes in 1..(maxEdge.toLong() * maxEdge * 4)) {
            "The image decoder exceeded the supported bitmap size. Resize the image before importing."
        }
    }

    fun supportsSignature(bytes: ByteArray): Boolean {
        fun ascii(offset: Int, length: Int): String =
            if (offset + length <= bytes.size) String(bytes, offset, length, Charsets.US_ASCII) else ""
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val webp = ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP"
        val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
        val heif = ascii(4, 4) == "ftyp" && ((8 until bytes.size - 3 step 4).any { ascii(it, 4) in heifBrands })
        return jpeg || png || webp || heif
    }
}
