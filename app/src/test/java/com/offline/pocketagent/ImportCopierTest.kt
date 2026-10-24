package com.offline.pocketagent

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportCopierTest {
    @Test fun negativeAndUnderreportedProviderSizesCannotBypassActualLimit() {
        for (reported in listOf(-1L, 0L, 2L)) inDirectory { directory ->
            val destination = File(directory, "original")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    ImportCopier.copy(ByteArrayInputStream(ByteArray(11)), destination, 10, reported, availableSpace = ::ampleSpace)
                }
            }
            assertFalse(destination.exists())
            assertFalse(File(directory, "original.part").exists())
        }
    }

    @Test fun hashMatchesTheBytesActuallyPublished() = inDirectory { directory ->
        val bytes = "abc".toByteArray()
        val destination = File(directory, "model.gguf")
        val receipt = runBlocking {
            ImportCopier.copy(ByteArrayInputStream(bytes), destination, 100, -1L, hash = true, availableSpace = ::ampleSpace)
        }
        assertArrayEquals(bytes, receipt.file.readBytes())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", receipt.sha256)
        assertFalse(File(directory, "model.gguf.part").exists())
    }

    @Test fun decliningFreeSpaceStopsMidCopyAndRemovesPartialOutput() = inDirectory { directory ->
        val destination = File(directory, "large")
        var checks = 0
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ImportCopier.copy(
                    Zeroes(10 * 1024 * 1024), destination, 12L * 1024 * 1024,
                    availableSpace = { checks++; if (checks == 1) ampleSpace() else ImportCopier.RESERVED_BYTES + 1024 },
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("nearly full"))
        assertEquals(2, checks)
        assertFalse(destination.exists())
        assertFalse(File(directory, "large.part").exists())
    }

    @Test fun cancellationAfterAReadCannotPublishTheFile() = inDirectory { directory ->
        val destination = File(directory, "original")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = currentCoroutineContext()[Job]!!
                val source = object : InputStream() {
                    override fun read(): Int = error("Bulk reads expected")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        buffer[offset] = 1
                        job.cancel()
                        return 1
                    }
                }
                ImportCopier.copy(source, destination, 100, availableSpace = ::ampleSpace)
            }
        }
        assertFalse(destination.exists())
        assertFalse(File(directory, "original.part").exists())
    }

    @Test fun existingDestinationIsNeverOverwritten() = inDirectory { directory ->
        val destination = File(directory, "original").apply { writeText("keep") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ImportCopier.copy(ByteArrayInputStream("replace".toByteArray()), destination, 100, availableSpace = ::ampleSpace) }
        }
        assertEquals("keep", destination.readText())
    }

    @Test fun destinationCreatedDuringCopyIsPreserved() = inDirectory { directory ->
        val destination = File(directory, "original")
        var firstRead = true
        val source = object : ByteArrayInputStream("replace".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (firstRead) { destination.writeText("keep"); firstRead = false }
                return super.read(buffer, offset, length)
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { ImportCopier.copy(source, destination, 100, availableSpace = ::ampleSpace) }
        }
        assertEquals("keep", destination.readText())
        assertFalse(File(directory, "original.part").exists())
    }

    @Test fun emptyFilesAndBrokenProvidersDoNotLeavePendingFiles() {
        val sources = listOf(ByteArrayInputStream(byteArrayOf()), object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        })
        for (source in sources) inDirectory { directory ->
            val destination = File(directory, "original")
            assertThrows(Exception::class.java) {
                runBlocking { ImportCopier.copy(source, destination, 100, availableSpace = ::ampleSpace) }
            }
            assertFalse(destination.exists())
            assertFalse(File(directory, "original.part").exists())
        }
    }

    @Test fun existingPendingFileDoesNotBelongToThisCopy() = inDirectory { directory ->
        val destination = File(directory, "original")
        val pending = File(directory, "original.part").apply { writeText("other import") }
        assertThrows(IOException::class.java) {
            runBlocking { ImportCopier.copy(ByteArrayInputStream("data".toByteArray()), destination, 100, availableSpace = ::ampleSpace) }
        }
        assertEquals("other import", pending.readText())
        assertFalse(destination.exists())
    }

    private class Zeroes(private var remaining: Int) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length, remaining)
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private fun ampleSpace() = 1024L * 1024 * 1024

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("pocket-import-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
