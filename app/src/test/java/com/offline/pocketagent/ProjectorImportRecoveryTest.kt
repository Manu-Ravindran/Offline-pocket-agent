package com.offline.pocketagent

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectorImportRecoveryTest {
    @Test fun aMissingTargetCanBeImportedEvenWhenOldPreferencesPointToIt() = inDirectory { directory ->
        val target = File(directory, "mmproj-model.gguf")
        val plan = ProjectorImportRecovery.prepare(target, target, false) { error("There are no bytes to verify") }
        assertEquals(ProjectorImportPlan.COPY_SELECTED_FILE, plan)
    }

    @Test fun anApprovedUnreferencedCopyIsReusedAfterInterruptedSetup() = inDirectory { directory ->
        val base = File(directory, "model.gguf").apply { writeText("unchanged base weights") }
        val target = File(directory, "mmproj-model.gguf").apply { writeText("approved projector") }
        var verifications = 0
        val plan = ProjectorImportRecovery.prepare(target, null, false) {
            verifications++
            require(it.readText() == "approved projector")
        }
        assertEquals(ProjectorImportPlan.REUSE_VERIFIED_ORPHAN, plan)
        assertEquals(1, verifications)
        assertEquals("approved projector", target.readText())
        assertEquals("unchanged base weights", base.readText())
    }

    @Test fun invalidUnreferencedBytesAreRemovedBeforeAReplacementCopy() = inDirectory { directory ->
        val base = File(directory, "model.gguf").apply { writeText("keep base") }
        val target = File(directory, "mmproj-model.gguf").apply { writeText("wrong download") }
        val plan = ProjectorImportRecovery.prepare(target, null, false) { throw IllegalArgumentException("Not approved") }
        assertEquals(ProjectorImportPlan.COPY_SELECTED_FILE, plan)
        assertFalse(target.exists())
        assertEquals("keep base", base.readText())
    }

    @Test fun anAlreadySavedPairIsNeitherReverifiedNorDeleted() = inDirectory { directory ->
        val target = File(directory, "mmproj-model.gguf").apply { writeText("saved pair") }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectorImportRecovery.prepare(target, target, false) { error("The saved pair must be left alone") }
        }
        assertEquals("saved pair", target.readText())
    }

    @Test fun anUnapprovedCopyThatMayBeLoadedIsNeverDeleted() = inDirectory { directory ->
        val target = File(directory, "mmproj-model.gguf").apply { writeText("possibly loaded") }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectorImportRecovery.prepare(target, null, true) { throw IllegalArgumentException("Not approved") }
        }
        assertEquals("possibly loaded", target.readText())
    }

    @Test fun aFileChangedDuringVerificationIsPreservedForANewAttempt() = inDirectory { directory ->
        val target = File(directory, "mmproj-model.gguf").apply { writeText("old") }
        assertThrows(IllegalStateException::class.java) {
            ProjectorImportRecovery.prepare(target, null, false) {
                it.writeText("a different file was published")
                throw IllegalArgumentException("Changed during verification")
            }
        }
        assertEquals("a different file was published", target.readText())
    }

    @Test fun aVerificationIoFailureDoesNotProveTheFileIsInvalid() = inDirectory { directory ->
        val target = File(directory, "mmproj-model.gguf").apply { writeText("keep on transient failure") }
        assertThrows(IOException::class.java) {
            ProjectorImportRecovery.prepare(target, null, false) { throw IOException("Transient read failure") }
        }
        assertEquals("keep on transient failure", target.readText())
    }

    @Test fun aRejectedOrphanSymlinkCannotDeleteItsOutsideTarget() = inDirectory { directory ->
        val outside = File(directory, "source-original.gguf").apply { writeText("keep source original") }
        val target = File(directory, "mmproj-model.gguf")
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        val plan = ProjectorImportRecovery.prepare(target, null, false) {
            require(!Files.isSymbolicLink(it.toPath())) { "Not an app-private regular file" }
        }
        assertEquals(ProjectorImportPlan.COPY_SELECTED_FILE, plan)
        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertEquals("keep source original", outside.readText())
    }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("pocket-projector-recovery").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
