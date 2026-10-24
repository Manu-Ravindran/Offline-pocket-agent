package com.offline.pocketagent.security

import com.offline.pocketagent.security.TrustedModelPolicy.Role
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrustedModelPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val modelBytes = "GGUFapproved-fixture-model".toByteArray()
    private val projectorBytes = "GGUFapproved-fixture-projector".toByteArray()

    @Test fun verifiesActualBytesAndSharesProofAcrossInstances() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, modelBytes)
        val store = MemoryProofStore()
        policy(root, store).verify(model, Role.MODEL)
        assertEquals(1, store.writes)
        policy(root, store).verify(model, Role.MODEL)
        assertEquals("An unchanged file should reuse its private proof", 1, store.writes)
        assertFalse(Files.getPosixFilePermissions(model.toPath()).contains(PosixFilePermission.OWNER_WRITE))
    }

    @Test fun forgedGgufHeaderDoesNotEstablishTrust() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, "GGUFunreviewed-model".toByteArray())
        val store = MemoryProofStore()
        expectRejected { policy(root, store).verify(model, Role.MODEL) }
        assertTrue(store.values.isEmpty())
    }

    @Test fun suppliedImportDigestMustMatchAllowlist() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, modelBytes)
        val store = MemoryProofStore()
        expectRejected { policy(root, store).verify(model, Role.MODEL, "0".repeat(64)) }
        assertTrue(store.values.isEmpty())
        policy(root, store).verify(model, Role.MODEL, hash(modelBytes))
        assertEquals(1, store.writes)
    }

    @Test fun roleAndExpectedPrivateFileNameMustMatch() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, modelBytes)
        expectRejected { policy(root, MemoryProofStore()).verify(model, Role.PROJECTOR) }
        val projector = File(model.parentFile, Role.PROJECTOR.fileName).apply { writeBytes(modelBytes) }
        expectRejected { policy(root, MemoryProofStore()).verify(projector, Role.PROJECTOR) }
    }

    @Test fun rejectsOutsidePathsNonUuidFoldersAndSymlinks() {
        val root = temporary.newFolder("models")
        val outside = temporary.newFile("model.gguf").apply { writeBytes(modelBytes) }
        val invalidDirectory = File(root, "external").apply { mkdir() }
        val invalid = File(invalidDirectory, Role.MODEL.fileName).apply { writeBytes(modelBytes) }
        val verifier = policy(root, MemoryProofStore())
        expectRejected { verifier.verify(outside, Role.MODEL) }
        expectRejected { verifier.verify(invalid, Role.MODEL) }
        val uuidDirectory = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val link = File(uuidDirectory, Role.MODEL.fileName)
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        expectRejected { verifier.verify(link, Role.MODEL) }
    }

    @Test fun changedSizeInvalidatesProofEvenWithTheOriginalTimestamp() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, modelBytes)
        val store = MemoryProofStore()
        val verifier = policy(root, store)
        verifier.verify(model, Role.MODEL)
        val originalModified = model.lastModified()
        check(model.setWritable(true))
        model.writeText("GGUFmodified-unapproved-bytes")
        check(model.setLastModified(originalModified))
        expectRejected { verifier.verify(model, Role.MODEL) }
        assertEquals(1, store.writes)
    }

    @Test fun changedTimestampRehashesAndRefreshesAnOtherwiseApprovedFile() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, modelBytes)
        val store = MemoryProofStore()
        val verifier = policy(root, store)
        verifier.verify(model, Role.MODEL)
        check(model.setLastModified(model.lastModified() + 2_000))
        verifier.verify(model, Role.MODEL)
        assertEquals(2, store.writes)
    }

    @Test fun copiedProofCannotAuthorizeAnotherCanonicalPath() {
        val root = temporary.newFolder("models")
        val trusted = modelFile(root, Role.MODEL, modelBytes)
        val store = MemoryProofStore()
        val verifier = policy(root, store)
        verifier.verify(trusted, Role.MODEL)
        val otherBytes = modelBytes.copyOf().apply { this[4] = 'X'.code.toByte() }
        val other = modelFile(root, Role.MODEL, otherBytes)
        check(other.setLastModified(trusted.lastModified()))
        store.forcedRead = store.values.values.single()
        expectRejected { verifier.verify(other, Role.MODEL) }
    }

    @Test fun unknownRoleAndMalformedProofFallBackToActualHash() {
        val root = temporary.newFolder("models")
        val model = modelFile(root, Role.MODEL, modelBytes)
        val store = MemoryProofStore()
        val verifier = policy(root, store)
        verifier.verify(model, Role.MODEL)
        val originalModified = model.lastModified()
        val proof = Properties().apply { load(store.values.values.single().reader()) }
        proof.setProperty("role", "UNTRUSTED")
        store.forcedRead = java.io.StringWriter().also { proof.store(it, null) }.toString()
        check(model.setWritable(true))
        model.writeBytes(modelBytes.copyOf().apply { this[4] = 'X'.code.toByte() })
        check(model.setLastModified(originalModified))
        expectRejected { verifier.verify(model, Role.MODEL) }
        store.forcedRead = "not a proof"
        expectRejected { verifier.verify(model, Role.MODEL) }
    }

    private fun policy(root: File, store: MemoryProofStore) = TrustedModelPolicy(
        root, store, mapOf(Role.MODEL to hash(modelBytes), Role.PROJECTOR to hash(projectorBytes)),
    )

    private fun modelFile(root: File, role: Role, bytes: ByteArray): File =
        File(File(root, UUID.randomUUID().toString()).apply { mkdir() }, role.fileName).apply { writeBytes(bytes) }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun expectRejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected the untrusted artifact to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected policy rejection.
        }
    }

    private class MemoryProofStore : TrustedModelPolicy.ProofStore {
        val values = mutableMapOf<String, String>()
        var forcedRead: String? = null
        var writes = 0
        override fun read(key: String): String? = forcedRead ?: values[key]
        override fun write(key: String, value: String): Boolean {
            values[key] = value
            writes++
            return true
        }
    }
}
