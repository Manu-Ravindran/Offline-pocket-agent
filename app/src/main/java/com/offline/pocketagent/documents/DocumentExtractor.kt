package com.offline.pocketagent.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
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
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** All document processing stays on-device; the OCR model is bundled in the APK. */
class DocumentExtractor(private val limits: ExtractionLimits = ExtractionLimits()) {
    suspend fun extract(file: File, displayName: String, mimeType: String? = null): ExtractedDocument =
        withContext(Dispatchers.IO) {
            require(file.isFile) { "The selected file could not be opened." }
            require(file.length() <= limits.maxFileBytes) {
                "This file exceeds the ${limits.maxFileBytes / (1024 * 1024)} MB import limit."
            }
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

    private suspend fun extractPdf(file: File, displayName: String): ExtractedDocument {
        val output = SectionCollector(limits.maxTextChars)
        var usedOcr = false
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
            renderer.use {
                if (renderer.pageCount > limits.maxPages) {
                    output.warnings += "Only the first ${limits.maxPages} of ${renderer.pageCount} PDF pages were read."
                }
                for (index in 0 until minOf(renderer.pageCount, limits.maxPages)) {
                    coroutineContext.ensureActive()
                    if (output.full) break
                    renderer.openPage(index).use { page ->
                        val nativeText = if (Build.VERSION.SDK_INT >= 35) {
                            runCatching { page.textContents.joinToString("\n") { it.text } }.getOrDefault("")
                        } else ""
                        val text: String
                        val kind: String
                        if (nativeText.isNotBlank()) {
                            text = nativeText
                            kind = "pdf_text"
                        } else {
                            val scale = limits.maxImageEdge.toDouble() / max(page.width, page.height).coerceAtLeast(1)
                            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            } catch (error: Exception) {
                                bitmap.recycle()
                                throw error
                            }
                            text = recognizeAndRelease(bitmap)
                            kind = "pdf_ocr"
                            usedOcr = true
                        }
                        if (text.isBlank()) {
                            output.warnings += "No readable text was found on PDF page ${index + 1}."
                        } else output.add("page ${index + 1}", text.trim(), kind)
                    }
                }
            }
        } catch (error: SecurityException) {
            throw IllegalArgumentException("This PDF is encrypted or password protected. Import an unlocked copy.", error)
        }
        if (usedOcr) {
            output.warnings += "Some PDF text was recognized from page images. Check OCR text against the original before relying on names or numbers."
        }
        output.warnings += "PDF extraction reads text; diagrams, charts, and image-only meaning require a vision model."
        return ExtractedDocument(displayName, "pdf", output.sections, output.warnings.distinct())
    }

    private suspend fun extractImage(file: File, displayName: String): ExtractedDocument {
        val bitmap = decodeBoundedImage(file)
        val text = recognizeAndRelease(bitmap)
        val output = SectionCollector(limits.maxTextChars)
        output.add("image OCR", text.trim(), "image_ocr")
        if (text.isBlank()) output.warnings += "No readable text was found in this image."
        output.warnings += "Image import currently extracts printed Latin-script text. General visual understanding requires a vision model."
        return ExtractedDocument(displayName, "image", output.sections, output.warnings.distinct(), file.absolutePath)
    }

    private fun decodeBoundedImage(file: File): Bitmap {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, dimensions)
        require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "This image format could not be decoded." }
        var sample = 1
        while (max(dimensions.outWidth, dimensions.outHeight) / sample > limits.maxImageEdge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) { "The selected image could not be decoded." }
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
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private suspend fun recognizeAndRelease(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (error: Exception) {
            bitmap.recycle()
            recognizer.close()
            throw error
        }
        // ML Kit tasks can outlive coroutine cancellation. Release only when the task is done.
        task.addOnCompleteListener {
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
