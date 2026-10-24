package com.offline.pocketagent.security

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF's native parser runs in a separate Android isolated UID. It cannot open the
 * app's document/model directories and receives only a read-only descriptor.
 */
class PdfSandboxService : Service() {
    private val watchdog = Handler(Looper.getMainLooper())
    private val pipeWriters = Executors.newFixedThreadPool(1)
    private var isolated = false

    override fun onCreate() {
        super.onCreate()
        isolated = Process.myUid() != applicationInfo.uid
        check(isolated) { "PDF processing requires an isolated service." }
    }

    private val binder = object : IPdfSandbox.Stub() {
        override fun pageCount(source: ParcelFileDescriptor): Int = withRenderer(source) { renderer ->
            renderer.pageCount.also { require(it >= 0) { "This PDF has an invalid page count." } }
        }

        override fun readPage(
            source: ParcelFileDescriptor,
            pageIndex: Int,
            maxEdge: Int,
            maxTextChars: Int,
            metadata: IntArray,
            text: Array<String?>,
        ): ParcelFileDescriptor = withRenderer(source) { renderer ->
            require(metadata.size == 4 && text.size == 1) { "Invalid PDF response buffers." }
            require(pageIndex in 0 until renderer.pageCount) { "This PDF page does not exist." }
            require(maxEdge in 1..PdfSandboxProtocol.MAX_EDGE) { "Invalid PDF render size." }
            require(maxTextChars in 1..PdfSandboxProtocol.MAX_PAGE_TEXT) { "Invalid PDF text limit." }
            metadata.fill(0)
            text[0] = ""
            renderer.openPage(pageIndex).use { page ->
                if (Build.VERSION.SDK_INT >= 35) {
                    val nativeText = StringBuilder()
                    var truncated = false
                    for (content in page.textContents) {
                        val remaining = maxTextChars - nativeText.length
                        if (remaining <= 0) { truncated = true; break }
                        if (nativeText.isNotEmpty()) nativeText.append('\n')
                        val available = (maxTextChars - nativeText.length).coerceAtLeast(0)
                        nativeText.append(content.text.take(available))
                        if (content.text.length > available) { truncated = true; break }
                    }
                    if (nativeText.isNotBlank()) {
                        text[0] = nativeText.toString()
                        metadata[2] = if (truncated) 1 else 0
                        return@use emptyPipe()
                    }
                }
                require(page.width > 0 && page.height > 0) { "This PDF page has invalid dimensions." }
                val scale = maxEdge.toDouble() / max(page.width, page.height)
                val width = (page.width * scale).roundToInt().coerceIn(1, maxEdge)
                val height = (page.height * scale).roundToInt().coerceIn(1, maxEdge)
                val byteCount = PdfSandboxProtocol.pixelBytes(width, height, maxEdge)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pixels = try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    check(bitmap.byteCount == byteCount) { "Unexpected PDF pixel layout." }
                    ByteBuffer.allocate(byteCount).also(bitmap::copyPixelsToBuffer).array()
                } finally {
                    bitmap.recycle()
                }
                metadata[0] = width
                metadata[1] = height
                metadata[3] = byteCount
                rawPixelPipe(pixels)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun <T> withRenderer(source: ParcelFileDescriptor, block: (PdfRenderer) -> T): T {
        val terminate = Runnable { if (isolated) Process.killProcess(Process.myPid()) }
        watchdog.postDelayed(terminate, 20_000L)
        try {
            require(source.statSize in 1..PdfSandboxProtocol.MAX_FILE_BYTES) {
                "The PDF descriptor is empty, too large, or not a regular file."
            }
            val renderer = try {
                PdfRenderer(source)
            } catch (error: Throwable) {
                source.close()
                throw error
            }
            return renderer.use(block)
        } catch (error: Throwable) {
            runCatching { source.close() }
            throw error
        } finally {
            watchdog.removeCallbacks(terminate)
        }
    }

    private fun emptyPipe(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[1].close()
        return pipe[0]
    }

    private fun rawPixelPipe(pixels: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val terminate = Runnable { if (isolated) Process.killProcess(Process.myPid()) }
        watchdog.postDelayed(terminate, 15_000L)
        try {
            pipeWriters.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pixels) }
                } catch (_: java.io.IOException) {
                    // The caller may have cancelled and closed its end of the pipe.
                } finally {
                    watchdog.removeCallbacks(terminate)
                }
            }
        } catch (error: RuntimeException) {
            watchdog.removeCallbacks(terminate)
            pipe.forEach { runCatching { it.close() } }
            throw error
        }
        // AIDL writes the return value with PARCELABLE_WRITE_RETURN_VALUE, which
        // transfers/closes this local descriptor after Binder duplicates it.
        return pipe[0]
    }

    override fun onDestroy() {
        pipeWriters.shutdownNow()
        watchdog.removeCallbacksAndMessages(null)
        super.onDestroy()
        // An unbound sandbox must not retain a hung native worker or descriptors.
        if (isolated) Process.killProcess(Process.myPid())
    }
}

internal object PdfSandboxProtocol {
    const val MAX_EDGE = 2_048
    const val MAX_PAGE_TEXT = 40_000
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    fun pixelBytes(width: Int, height: Int, maxEdge: Int): Int {
        require(maxEdge in 1..MAX_EDGE && width in 1..maxEdge && height in 1..maxEdge) {
            "The PDF renderer returned invalid image dimensions."
        }
        val count = width.toLong() * height * 4L
        require(count <= MAX_EDGE.toLong() * MAX_EDGE * 4L) { "The PDF page is too large." }
        return count.toInt()
    }
}
