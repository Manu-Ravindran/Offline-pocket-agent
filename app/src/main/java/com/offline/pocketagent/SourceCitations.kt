package com.offline.pocketagent

/** Validate source-shaped references without treating normal Markdown/literal brackets as citations. */
internal object SourceCitations {
    private val closed = Regex("\\[([^\\[\\]\\r\\n]+)\\]")
    private val openReference = Regex("\\[([A-Za-z0-9_-]{1,80}:[^\\[\\]\\r\\n]{0,160})(?=$|[\\r\\n])")
    private val missingOpen = Regex("(?<![A-Za-z0-9_\\[])\\b([A-Za-z0-9_-]{1,80}:[^\\[\\]\\r\\n]{1,160})\\]")
    private val unknownPrefix = Regex("(?:[A-Za-z_][A-Za-z0-9_-]{0,79}|[0-9a-fA-F]{8})")
    private val knownTail = Regex("(?i)(?:[0-9]+|image|[^\\[\\]\\r\\n]{1,100}![A-Z]{1,3}[1-9][0-9]{0,6}(?::[A-Z]{1,3}[1-9][0-9]{0,6})?)")
    private val danglingTail = Regex("(?i)^(?:[0-9]+|image)\\b")
    private val unqualifiedPage = Regex("(?i)^page\\s*[0-9]+$")

    fun invalid(text: String, allowed: Set<String>): List<String> {
        val prefixes = allowed.map { it.substringBefore(':') }.toSet()
        fun looksLikeReference(label: String, dangling: Boolean = false): Boolean {
            if (unqualifiedPage.matches(label)) return true
            if (':' !in label) return false
            val prefix = label.substringBefore(':')
            val tail = label.substringAfter(':')
            if (prefix in prefixes) return true
            return unknownPrefix.matches(prefix) && (knownTail.matches(tail) || dangling && danglingTail.containsMatchIn(tail))
        }
        val errors = linkedSetOf<String>()
        for (match in closed.findAll(text)) {
            val label = match.groupValues[1].trim()
            if (looksLikeReference(label) && label !in allowed) errors += label
        }
        // Literal [USD], [1, 2, 3], [12:30], and [label](url) remain valid prose.
        val remainder = closed.replace(text, "")
        for (pattern in listOf(openReference, missingOpen)) {
            for (match in pattern.findAll(remainder)) {
                val label = match.groupValues[1].trim()
                if (looksLikeReference(label, dangling = true)) errors += label
            }
        }
        return errors.toList()
    }
}
