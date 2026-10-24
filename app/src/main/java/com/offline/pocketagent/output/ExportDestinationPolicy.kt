package com.offline.pocketagent.output

import java.util.Locale

/** A picker result may designate another provider, never an app-private filesystem path. */
object ExportDestinationPolicy {
    fun requireExternalContent(scheme: String?, authority: String?, ownAuthority: String) {
        val normalizedAuthority = authority?.substringAfterLast('@')?.lowercase(Locale.ROOT)
        require(scheme == "content" && !normalizedAuthority.isNullOrBlank() &&
            normalizedAuthority != ownAuthority.lowercase(Locale.ROOT)) {
            "Choose a document folder using the Android save picker. This destination is not supported."
        }
    }
}
