package com.offline.pocketagent.security

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Only reviewed model artifacts may cross the native GGUF parser boundary. */
class TrustedModelPolicy internal constructor(
    private val modelsRoot: File,
    private val proofStore: ProofStore,
    private val approvedDigests: Map<Role, String> = APPROVED_DIGESTS,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "models"),
        PreferenceProofStore(
            context.applicationContext.getSharedPreferences("trusted_models_v1", Context.MODE_PRIVATE),
        ),
    )

    enum class Role(val fileName: String) {
        MODEL("model.gguf"),
        PROJECTOR("mmproj-model.gguf"),
    }

    /**
     * [knownDigest] is for the app's import pipeline, which hashes bytes while
     * copying them into private storage. Never accept this value from a document,
     * model sidecar, intent extra, or user-provided metadata.
     *
     * A cached proof is valid only inside this app's private model directory and
     * while its path, role, length, modification time, and approved hash match.
     * The private app UID is the trust boundary; rooted or modified app data is
     * outside it. Legacy files without a matching proof are hashed once.
     */
    fun verify(file: File, role: Role, knownDigest: String? = null) = synchronized(PROOF_LOCK) {
        val canonical = checkedPrivatePath(file, role)
        val expected = checkNotNull(approvedDigests[role]) { "No approved artifact is configured for $role." }
        val key = sha256(canonical.path.toByteArray(Charsets.UTF_8))
        val beforeLength = canonical.length()
        val beforeModified = canonical.lastModified()
        require(beforeLength >= 4L && beforeModified > 0L) { "The model file is empty or unavailable." }
        val proof = proofStore.read(key)?.let(Proof::parse)
        if (knownDigest == null && proof?.matches(canonical, role, expected, beforeLength, beforeModified) == true) {
            check(canonical.setReadOnly()) { "Could not protect the verified model file." }
            return@synchronized
        }

        // Protect first, then hash the exact local file that the native loader will
        // receive. Private imports are closed before this method is called.
        check(canonical.setReadOnly()) { "Could not protect the model file before verification." }
        val actual = if (knownDigest == null) hashFile(canonical) else {
            require(knownDigest.matches(Regex("[0-9a-fA-F]{64}"))) { "The model checksum is invalid." }
            knownDigest.lowercase()
        }
        require(actual == expected) {
            "This ${if (role == Role.MODEL) "model" else "vision projector"} is not an approved file. " +
                "Import the exact Qwen3.5-4B files linked in the setup guide. Custom GGUF files are not enabled."
        }
        require(canonical.length() == beforeLength && canonical.lastModified() == beforeModified) {
            "The model changed while it was being verified. Import it again."
        }
        val verified = Proof(canonical.path, role.name, expected, beforeLength, beforeModified)
        check(proofStore.write(key, verified.encode())) { "Could not save model verification. Please try again." }
    }

    private fun checkedPrivatePath(file: File, role: Role): File {
        val canonical = file.canonicalFile
        val root = modelsRoot.canonicalFile
        val supplied = file.absoluteFile.normalize()
        val suppliedRoot = modelsRoot.absoluteFile.normalize()
        val parent = canonical.parentFile
        require(
            file.isAbsolute &&
                supplied.parentFile?.parentFile == suppliedRoot &&
                canonical.isFile &&
                canonical.canRead() &&
                canonical.name == role.fileName &&
                parent?.parentFile == root &&
                validUuid(parent.name) &&
                !Files.isSymbolicLink(supplied.toPath()) &&
                !Files.isSymbolicLink(supplied.parentFile.toPath()),
        ) { "Models must be imported into this app's private model folder before they can be loaded." }
        return canonical
    }

    private fun validUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private data class Proof(
        val path: String,
        val role: String,
        val digest: String,
        val length: Long,
        val modified: Long,
    ) {
        fun matches(file: File, expectedRole: Role, expectedDigest: String, size: Long, mtime: Long): Boolean =
            path == file.path && role == expectedRole.name && digest == expectedDigest && length == size && modified == mtime

        fun encode(): String = StringWriter().also { output ->
            Properties().apply {
                setProperty("schema", "1")
                setProperty("path", path)
                setProperty("role", role)
                setProperty("sha256", digest)
                setProperty("length", length.toString())
                setProperty("modified", modified.toString())
                store(output, null)
            }
        }.toString()

        companion object {
            fun parse(value: String): Proof? = runCatching {
                val properties = Properties().apply { load(StringReader(value)) }
                require(properties.getProperty("schema") == "1")
                val role = properties.getProperty("role")
                require(Role.entries.any { it.name == role })
                val digest = properties.getProperty("sha256")
                require(digest.matches(Regex("[0-9a-f]{64}")))
                Proof(
                    properties.getProperty("path"), role, digest,
                    properties.getProperty("length").toLong(),
                    properties.getProperty("modified").toLong(),
                )
            }.getOrNull()
        }
    }

    internal interface ProofStore {
        fun read(key: String): String?
        fun write(key: String, value: String): Boolean
    }

    private class PreferenceProofStore(private val preferences: SharedPreferences) : ProofStore {
        override fun read(key: String): String? = preferences.getString(key, null)
        override fun write(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()
    }

    companion object {
        private val PROOF_LOCK = Any()
        private val APPROVED_DIGESTS = mapOf(
            Role.MODEL to "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            Role.PROJECTOR to "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864",
        )
        private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
