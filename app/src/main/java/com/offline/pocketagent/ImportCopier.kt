package com.offline.pocketagent

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal data class ImportReceipt(val file: File, val sha256: String?)

/** Copies a provider stream without trusting its advertised size or publishing partial bytes. */
internal object ImportCopier {
    internal const val RESERVED_BYTES = 128L * 1024 * 1024
    private const val SPACE_CHECK_INTERVAL = 8L * 1024 * 1024
    private const val BUFFER_BYTES = 128 * 1024
    private const val MAX_DURATION_NANOS = 15L * 60 * 1_000_000_000

    /**
     * The caller owns [input]. Time and cancellation checks run between reads;
     * an uncooperative content provider can still block an individual read.
     */
    suspend fun copy(
        input: InputStream,
        destination: File,
        maximum: Long,
        reportedSize: Long? = null,
        hash: Boolean = false,
        availableSpace: () -> Long = { destination.parentFile!!.usableSpace },
    ): ImportReceipt {
        require(maximum > 0) { "The import size limit must be positive." }
        val knownSize = reportedSize?.takeIf { it >= 0 }
        require(knownSize == null || knownSize <= maximum) { "This file exceeds the import size limit." }
        check(!destination.exists()) { "An imported file already exists at this destination." }
        coroutineContext.ensureActive()
        if (knownSize != null) checkSpace(availableSpace(), knownSize)

        val pending = File(destination.parentFile, destination.name + ".part")
        val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
        val startedAt = System.nanoTime()
        var ownsPending = false
        try {
            Files.newOutputStream(pending.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { stream ->
                ownsPending = true
                stream.buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = 0L
                    var nextSpaceCheck = 0L
                    var emptyReads = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        require(System.nanoTime() - startedAt <= MAX_DURATION_NANOS) {
                            "This file took too long to import. Copy it to local phone storage and try again."
                        }
                        if (copied >= nextSpaceCheck) {
                            // Reserve enough for the entire interval plus the next
                            // read, so the free-space floor holds between checks.
                            checkSpace(availableSpace(), SPACE_CHECK_INTERVAL + BUFFER_BYTES)
                            nextSpaceCheck = copied + SPACE_CHECK_INTERVAL
                        }
                        val read = input.read(buffer)
                        coroutineContext.ensureActive()
                        if (read < 0) break
                        if (read == 0) {
                            emptyReads++
                            if (emptyReads >= 16) throw IOException("The selected file provider is not returning data.")
                            continue
                        }
                        emptyReads = 0
                        require(read <= buffer.size && copied <= maximum - read.toLong()) {
                            "This file exceeds the import size limit."
                        }
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        copied += read
                    }
                    require(copied > 0L) { "The selected file is empty." }
                }
            }
            coroutineContext.ensureActive()
            // Same-directory publication is atomic on app-private Android storage.
            // Omitting REPLACE_EXISTING preserves any existing destination.
            Files.move(pending.toPath(), destination.toPath())
            return ImportReceipt(destination, digest?.digest()?.joinToString("") { byte -> "%02x".format(byte) })
        } finally {
            if (ownsPending) pending.delete()
        }
    }

    private fun checkSpace(space: Long, required: Long) {
        require(space >= RESERVED_BYTES && required <= space - RESERVED_BYTES) {
            "Phone storage is nearly full. Free some space before importing this file."
        }
    }
}
