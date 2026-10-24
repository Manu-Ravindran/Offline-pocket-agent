package com.offline.pocketagent

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal enum class ProjectorImportPlan { COPY_SELECTED_FILE, REUSE_VERIFIED_ORPHAN }

/** Caller must verify the saved base model and its private UUID folder before preparing this fixed-name target. */
internal object ProjectorImportRecovery {
    fun prepare(
        target: File,
        savedProjector: File?,
        targetMayBeInUse: Boolean,
        verifyApproved: (File) -> Unit,
    ): ProjectorImportPlan {
        require(target.name == "mmproj-model.gguf") { "Unexpected vision import destination." }
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return ProjectorImportPlan.COPY_SELECTED_FILE
        require(savedProjector?.canonicalFile != target.canonicalFile) {
            "A vision file is already saved for this model. Use Retry saved model to activate the saved pair; replacing it requires importing the full pair."
        }
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(!before.isDirectory) { "The vision import destination is a folder. Import the full model pair again to repair setup." }
        try {
            verifyApproved(target)
            return ProjectorImportPlan.REUSE_VERIFIED_ORPHAN
        } catch (rejected: IllegalArgumentException) {
            require(!targetMayBeInUse) {
                "The existing vision file may still be loaded. Restart the app before repairing its import."
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(before.fileKey() == after.fileKey() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime()) {
                "The vision file changed during verification. Reopen Settings and try again."
            }
            Files.delete(path) // Unlinks a rejected symlink itself; never follows it to an outside file.
            return ProjectorImportPlan.COPY_SELECTED_FILE
        }
    }
}
