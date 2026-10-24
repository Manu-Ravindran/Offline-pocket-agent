package com.offline.pocketagent.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import com.offline.pocketagent.documents.ExtractionLimits
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** The host receives text/raw pixels only and never parses a PDF in its own UID. */
class PdfSandboxClient(context: Context) {
    private val app = context.applicationContext

    suspend fun extract(file: File, displayName: String, limits: ExtractionLimits): ExtractedDocument =
        withContext(Dispatchers.IO) {
            require(file.isFile && file.length() in 1..minOf(limits.maxFileBytes, PdfSandboxProtocol.MAX_FILE_BYTES)) {
                "The PDF is empty or exceeds the import size limit."
            }
            try {
              withTimeout(180_000L) {
                val connection = withTimeout(10_000L) { connect() }
                try {
                    val count = remote { withReadOnlyFile(file) { connection.api.pageCount(it) } }
                    require(count >= 0) { "The PDF renderer returned an invalid page count." }
                    val warnings = mutableListOf<String>()
                    val sections = mutableListOf<DocumentSection>()
                    if (count > limits.maxPages) warnings += "Only the first ${limits.maxPages} of $count PDF pages were read."
                    var usedChars = 0
                    var usedOcr = false
                    for (index in 0 until minOf(count, limits.maxPages)) {
                        coroutineContext.ensureActive()
                        val remaining = limits.maxTextChars - usedChars
                        if (remaining <= 0) {
                            warnings += "Text extraction stopped at the ${limits.maxTextChars}-character limit; the PDF may have additional content."
                            break
                        }
                        val edge = minOf(limits.maxImageEdge, PdfSandboxProtocol.MAX_EDGE)
                        val cap = minOf(remaining, PdfSandboxProtocol.MAX_PAGE_TEXT)
                        val metadata = IntArray(4)
                        val text = arrayOfNulls<String>(1)
                        val pixels = remote(dispose = { it.close() }) {
                            withReadOnlyFile(file) { descriptor ->
                                connection.api.readPage(descriptor, index, edge, cap, metadata, text)
                            }
                        }
                        try {
                            val nativeText = text[0].orEmpty()
                            require(nativeText.length <= cap && metadata[2] in 0..1) {
                                "The PDF renderer returned an oversized text response."
                            }
                            val pageText: String
                            val kind: String
                            if (nativeText.isNotBlank()) {
                                require(metadata[0] == 0 && metadata[1] == 0 && metadata[3] == 0) {
                                    "The PDF renderer returned inconsistent page data."
                                }
                                pageText = nativeText
                                kind = "pdf_text"
                            } else {
                                val byteCount = PdfSandboxProtocol.pixelBytes(metadata[0], metadata[1], edge)
                                require(metadata[3] == byteCount) { "The PDF renderer returned an invalid pixel count." }
                                val bytes = remote(dispose = {}) {
                                    ParcelFileDescriptor.AutoCloseInputStream(pixels).use { input ->
                                        val raw = ByteArray(byteCount)
                                        var read = 0
                                        while (read < raw.size) {
                                            val amount = input.read(raw, read, raw.size - read)
                                            require(amount > 0) { "The PDF renderer stopped before completing this page." }
                                            read += amount
                                        }
                                        require(input.read() == -1) { "The PDF renderer sent unexpected page data." }
                                        raw
                                    }
                                }
                                val bitmap = Bitmap.createBitmap(metadata[0], metadata[1], Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                                } catch (error: Throwable) {
                                    bitmap.recycle()
                                    throw error
                                }
                                pageText = recognizeAndRelease(bitmap)
                                kind = "pdf_ocr"
                                usedOcr = true
                            }
                            if (metadata[2] == 1) warnings += "Text on PDF page ${index + 1} exceeded the per-page extraction limit."
                            if (pageText.isBlank()) warnings += "No readable text was found on PDF page ${index + 1}."
                            else {
                                val kept = pageText.trim().take(remaining)
                                sections += DocumentSection("page ${index + 1}", kept, kind)
                                usedChars += kept.length
                                if (pageText.length > remaining) warnings += "Text extraction stopped at the ${limits.maxTextChars}-character limit; the PDF may have additional content."
                            }
                        } finally {
                            runCatching { pixels.close() }
                        }
                    }
                    if (usedOcr) warnings += "Some PDF text was recognized from page images. Check OCR text against the original before relying on names or numbers."
                    warnings += "PDF extraction reads text; diagrams, charts, and image-only meaning require a vision model."
                    ExtractedDocument(displayName, "pdf", sections, warnings.distinct())
                } catch (error: RemoteException) {
                    throw IllegalArgumentException("The isolated PDF reader stopped. This file may be damaged or too complex to read safely.", error)
                } catch (error: SecurityException) {
                    throw IllegalArgumentException("This PDF is encrypted or password protected. Import an unlocked copy.", error)
                } finally {
                    connection.binding.close()
                }
              }
            } catch (error: TimeoutCancellationException) {
                throw IllegalArgumentException("The PDF took too long to process safely. Try a smaller or simpler PDF.", error)
            }
        }

    private inline fun <T> withReadOnlyFile(file: File, block: (ParcelFileDescriptor) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(block)

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
        task.addOnCompleteListener(Executor { it.run() }) {
            if (!bitmap.isRecycled) bitmap.recycle()
            recognizer.close()
        }
        return withTimeout(45_000L) { task.await().text }
    }

    private suspend fun connect(): Connection = suspendCancellableCoroutine { continuation ->
        val binding = Binding(app)
        val service = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The isolated PDF reader is unavailable."))
                    binding.close()
                } else if (continuation.isActive) {
                    continuation.resume(Connection(IPdfSandbox.Stub.asInterface(binder), binding))
                } else binding.close()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit

            override fun onNullBinding(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The isolated PDF reader could not start."))
                binding.close()
            }

            override fun onBindingDied(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The isolated PDF reader stopped while starting."))
                binding.close()
            }
        }
        binding.service = service
        continuation.invokeOnCancellation { binding.close() }
        if (continuation.isActive) {
            try {
                val bound = app.bindService(Intent(app, PdfSandboxService::class.java), service, Context.BIND_AUTO_CREATE)
                binding.registered(bound)
                if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("The isolated PDF reader could not be bound."))
            } catch (error: Exception) {
                binding.close()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    /** Binder/native calls cannot interrupt the UI or keep a cancelled coroutine waiting. */
    private suspend fun <T : Any> remote(dispose: (T) -> Unit = {}, block: () -> T): T =
        withTimeout(30_000L) {
            suspendCancellableCoroutine { continuation ->
                val result = AtomicReference<T?>()
                val work = IPC_EXECUTOR.submit {
                    try {
                        val value = block()
                        result.set(value)
                        if (continuation.isActive) continuation.resume(value)
                        else result.getAndSet(null)?.let(dispose)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    result.getAndSet(null)?.let(dispose)
                    work.cancel(true)
                }
            }
        }

    private data class Connection(val api: IPdfSandbox, val binding: Binding)

    private class Binding(private val context: Context) {
        lateinit var service: ServiceConnection
        private var bound = false
        private var closed = false

        @Synchronized fun registered(value: Boolean) {
            bound = value
            if (closed && bound) unbind()
        }

        @Synchronized fun close() {
            closed = true
            if (bound) unbind()
        }

        private fun unbind() {
            bound = false
            runCatching { context.unbindService(service) }
        }
    }

    companion object {
        private val IPC_EXECUTOR = Executors.newFixedThreadPool(2)
    }
}
