package com.offline.pocketagent.diagnostics

import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Two private, generated log files. Callers must sanitize semantic fields before appending. */
class BoundedDiagnosticStore(
    private val directory: File,
    private val maxFileBytes: Int = 128 * 1024,
    private val maxEvents: Int = 256,
) {
    private val canonicalDirectory: File
    private val names = listOf("diagnostic-previous.log", "diagnostic-current.log")

    init {
        require(maxFileBytes >= 2 && maxEvents > 0)
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Diagnostic directory must not be a symbolic link.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic directory is unavailable.")
        canonicalDirectory = directory.canonicalFile
        checkedFiles()
    }

    /** Short stage events only; no token-by-token logging. Records are kept in chronological order. */
    @Synchronized fun append(line: String) {
        val event = singleLine(line, minOf(4095, maxFileBytes - 1))
        if (event.isBlank()) return
        val events = (readEvents().takeLast(maxEvents - 1) + event)
        val current = newestThatFit(events)
        val previous = newestThatFit(events.dropLast(current.size))
        val files = checkedFiles()
        write(files[0], previous)
        write(files[1], current)
    }

    @Synchronized fun snapshot(): String = readEvents().takeLast(maxEvents).joinToString("\n")

    /** Never removes any neighboring app data or user files. */
    @Synchronized fun clear() {
        checkedFiles().forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun readEvents(): List<String> = checkedFiles().flatMap { file ->
        if (!file.exists()) emptyList() else {
            if (file.length() > maxFileBytes) throw IOException("Diagnostic file exceeded its bounded size.")
            val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                val result = ByteArrayOutputStream(minOf(maxFileBytes, 8192))
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size().toLong() + count > maxFileBytes) throw IOException("Diagnostic file changed while reading.")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        }
    }

    private fun newestThatFit(events: List<String>): List<String> {
        var bytes = 0
        var count = 0
        for (event in events.asReversed()) {
            val size = event.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > maxFileBytes) break
            bytes += size
            count++
        }
        return events.takeLast(count)
    }

    private fun write(file: File, events: List<String>) {
        val content = if (events.isEmpty()) "" else events.joinToString("\n", postfix = "\n")
        val bytes = content.toByteArray(Charsets.UTF_8)
        check(bytes.size <= maxFileBytes)
        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { it.write(bytes) }
    }

    private fun checkedFiles(): List<File> {
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory ||
            directory.canonicalFile != canonicalDirectory) throw IOException("Diagnostic directory changed.")
        return names.map { name ->
            File(directory, name).also { file ->
                if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != canonicalDirectory ||
                    file.exists() && !file.isFile) throw IOException("Diagnostic file is not a private regular file.")
            }
        }
    }

    /** Bound by UTF-8 bytes without splitting a code point; normalize controls into one-line text. */
    private fun singleLine(value: String, maximumBytes: Int): String {
        val result = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < value.length) {
            val original = value.codePointAt(offset)
            offset += Character.charCount(original)
            val codePoint = when {
                original in 0xD800..0xDFFF -> 0xFFFD
                Character.isISOControl(original) || original == 0x2028 || original == 0x2029 -> 0x20
                else -> original
            }
            val count = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes + count > maximumBytes) break
            result.appendCodePoint(codePoint)
            bytes += count
        }
        return result.toString()
    }
}
