package com.offline.pocketagent.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BoundedDiagnosticStoreTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("pocket-diagnostic-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun rotationBoundsBothFilesAndPreservesNewestOrder() = fixture { root ->
        val store = BoundedDiagnosticStore(File(root, "logs"), maxFileBytes = 36, maxEvents = 8)
        repeat(20) { store.append("event-${it.toString().padStart(2, '0')}") }
        val lines = store.snapshot().lines()
        assertEquals((12..19).map { "event-$it" }, lines)
        val files = File(root, "logs").listFiles()!!.toList()
        assertEquals(2, files.size)
        assertTrue(files.all { it.length() <= 36 })
    }

    @Test fun totalRetainedEventsAreBoundedEvenWhenByteBudgetIsLarge() = fixture { root ->
        val store = BoundedDiagnosticStore(root, maxFileBytes = 1024, maxEvents = 3)
        repeat(8) { store.append("event-$it") }
        assertEquals(listOf("event-5", "event-6", "event-7"), store.snapshot().lines())
        val stored = root.listFiles()!!.flatMap { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(3, stored.size)
    }

    @Test fun unicodeEventsAreByteBoundedAndNeverEndInBrokenCodePoints() = fixture { root ->
        val store = BoundedDiagnosticStore(root, maxFileBytes = 18, maxEvents = 3)
        store.append("😀😀😀😀😀😀")
        assertEquals("😀😀😀😀", store.snapshot())
        assertTrue(root.listFiles()!!.all { it.length() <= 18 })
        assertFalse(store.snapshot().contains('\uFFFD'))
    }

    @Test fun controlsCannotInjectExtraLogRecords() = fixture { root ->
        val store = BoundedDiagnosticStore(root)
        store.append("first\nforged\r\nrecord\t\u0000\u2028end")
        assertEquals(1, store.snapshot().lines().size)
        assertFalse(store.snapshot().contains('\t'))
        assertTrue(store.snapshot().contains("end"))
    }

    @Test fun clearPreservesUnrelatedFilesAndStoreCanBeReused() = fixture { root ->
        val unrelated = File(root, "keep.txt").apply { writeText("user data") }
        val store = BoundedDiagnosticStore(root)
        store.append("stage-one")
        store.clear()
        assertEquals("", store.snapshot())
        assertEquals("user data", unrelated.readText())
        store.append("stage-two")
        assertEquals("stage-two", store.snapshot())
    }

    @Test fun symlinkedDirectoryAndLogFilesAreRejectedWithoutTouchingTarget() = fixture { root ->
        val outside = File(root, "outside").apply { mkdirs() }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { BoundedDiagnosticStore(alias) }
        val logs = File(root, "logs")
        val store = BoundedDiagnosticStore(logs)
        val target = File(outside, "sensitive").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(logs, "diagnostic-current.log").toPath(), target.toPath())
        assertThrows(IOException::class.java) { store.append("anything") }
        assertThrows(IOException::class.java) { store.snapshot() }
        assertThrows(IOException::class.java) { store.clear() }
        assertEquals("unchanged", target.readText())
    }
}
