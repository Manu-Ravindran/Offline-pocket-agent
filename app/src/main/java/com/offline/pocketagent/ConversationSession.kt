package com.offline.pocketagent

/** A snapshot is the full document and history scope of one request. Disk imports are never its default scope. */
internal data class ConversationSnapshot(
    val generation: Long,
    val documentIds: List<String>,
    val history: List<ConversationTurn>,
)

/**
 * Conversation state has no reference to models or files. Clearing a chat only
 * detaches its documents and history; the controller keeps imported copies and
 * the loaded model separately.
 */
internal class ConversationSession(
    private val maxHistoryTurns: Int = 6,
    private val maxAttachments: Int = 30,
) {
    private data class ScopedTurn(val turn: ConversationTurn, val documentIds: Set<String>)
    private val lock = Any()
    private val attachments = linkedSetOf<String>()
    private val turns = mutableListOf<ScopedTurn>()
    private var generation = 0L

    init {
        require(maxHistoryTurns > 0 && maxAttachments > 0)
    }

    fun attach(documentId: String) = synchronized(lock) {
        require(documentId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "The attachment identifier is invalid." }
        require(documentId in attachments || attachments.size < maxAttachments) {
            "A conversation can contain at most $maxAttachments attachments."
        }
        attachments.add(documentId)
        Unit
    }

    fun documentIds(): List<String> = synchronized(lock) { attachments.toList() }

    fun attachIfCurrent(documentId: String, expectedGeneration: Long): Boolean = synchronized(lock) {
        if (generation != expectedGeneration) return@synchronized false
        attach(documentId)
        true
    }

    fun isCurrent(snapshot: ConversationSnapshot): Boolean = synchronized(lock) {
        snapshot.generation == generation && snapshot.documentIds.all { it in attachments }
    }

    /** Null means this chat's attachments; an explicit empty list means no document context. */
    fun snapshot(requestedIds: List<String>? = null): ConversationSnapshot = synchronized(lock) {
        val ids = requestedIds?.distinct() ?: attachments.toList()
        require(ids.all { it in attachments }) {
            "An attached document is outside this conversation. Add that file to this chat before asking about it."
        }
        val allowed = ids.toSet()
        val scopedHistory = turns.filter { turn -> turn.documentIds.all { it in allowed } }
            .takeLast(maxHistoryTurns).map { it.turn }
        ConversationSnapshot(generation, ids.toList(), scopedHistory)
    }

    /** A late completion from a cleared conversation cannot reintroduce its context. */
    fun remember(snapshot: ConversationSnapshot, question: String, answer: String): Boolean = synchronized(lock) {
        if (snapshot.generation != generation || snapshot.documentIds.any { it !in attachments }) return@synchronized false
        turns += ScopedTurn(ConversationTurn(question.take(1_000), answer.take(2_000)), snapshot.documentIds.toSet())
        while (turns.size > maxHistoryTurns) turns.removeAt(0)
        true
    }

    fun detach(documentId: String): Boolean = synchronized(lock) {
        val removed = attachments.remove(documentId)
        if (removed) turns.removeAll { documentId in it.documentIds }
        removed
    }

    fun clear() = synchronized(lock) {
        generation++
        attachments.clear()
        turns.clear()
    }
}
