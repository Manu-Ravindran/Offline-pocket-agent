package com.offline.pocketagent.output

import java.io.File

data class PendingAnswerExport(val fileName: String, val mimeType: String, val cacheName: String)

/** Only generated export files live in the narrow FileProvider exports directory. */
class AnswerExportCache(cacheDirectory: File) {
    private val directory = File(cacheDirectory, "exports").apply { mkdirs() }

    fun stage(export: AnswerExport): PendingAnswerExport {
        val extension = export.fileName.substringAfterLast('.')
        require(extension in setOf("md", "txt", "html", "csv"))
        require(export.fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")))
        cleanupOldFiles()
        val file = File.createTempFile("answer-", ".$extension", directory)
        try { file.writeText(export.content, Charsets.UTF_8) }
        catch (error: Exception) { file.delete(); throw error }
        return PendingAnswerExport(export.fileName, export.mimeType, file.name)
    }

    fun file(pending: PendingAnswerExport): File {
        require(pending.cacheName.matches(Regex("answer-[A-Za-z0-9-]+\\.(md|txt|html|csv)"))) { "This export could not be reopened. Save the answer again." }
        val file = File(directory, pending.cacheName)
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile) { "This export is no longer available. Save the answer again." }
        return file
    }

    fun discard(pending: PendingAnswerExport?) {
        if (pending != null) runCatching { file(pending).delete() }
    }

    private fun cleanupOldFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1_000
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("answer-") && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
