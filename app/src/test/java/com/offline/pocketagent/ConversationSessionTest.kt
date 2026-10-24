package com.offline.pocketagent

import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
    @Test fun aFreshConversationHasNoPrivateArchiveScope() {
        val session = ConversationSession()
        val privateArchive = mapOf("oldA" to "Private A", "oldB" to "Private B")
        val scope = session.snapshot()
        assertTrue(scope.documentIds.isEmpty())
        assertTrue(scope.history.isEmpty())
        assertTrue(privateArchive.filterKeys { it in scope.documentIds }.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { session.snapshot(listOf("oldA")) }
    }

    @Test fun importAClearThenImportBScopesOnlyBAndErasesOldFacts() {
        val session = ConversationSession()
        val privateArchive = linkedMapOf("A" to "Confidential invoice A", "B" to "Report B")
        session.attach("A")
        assertTrue(session.remember(session.snapshot(), "Summarize A", "Invoice A contains confidential figures."))
        session.clear()
        session.attach("B")

        val scope = session.snapshot()
        assertEquals(listOf("B"), scope.documentIds)
        assertTrue(scope.history.isEmpty())
        assertEquals(listOf("Report B"), scope.documentIds.mapNotNull(privateArchive::get))
        assertTrue(privateArchive.containsKey("A")) // Detaching does not delete imported copies.
        assertThrows(IllegalArgumentException::class.java) { session.snapshot(listOf("A")) }
    }

    @Test fun nullAndEmptyScopesNeverExpandToThePrivateArchive() {
        val session = ConversationSession()
        session.attach("A")
        assertEquals(listOf("A"), session.snapshot(null).documentIds)
        assertTrue(session.snapshot(emptyList()).documentIds.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { session.snapshot(listOf("A", "oldB")) }
    }

    @Test fun explicitEmptyDocumentScopeDoesNotLeakPriorDocumentAnswersThroughHistory() {
        val session = ConversationSession()
        session.remember(session.snapshot(), "My name is Sam", "Hello Sam.")
        session.attach("A")
        session.remember(session.snapshot(), "Read A", "Secret from A.")
        val noDocuments = session.snapshot(emptyList())
        assertTrue(noDocuments.documentIds.isEmpty())
        assertEquals(listOf(ConversationTurn("My name is Sam", "Hello Sam.")), noDocuments.history)
    }

    @Test fun selectingBDoesNotPassAnswersPreviouslyScopedToA() {
        val session = ConversationSession()
        session.attach("A")
        session.attach("B")
        session.remember(session.snapshot(listOf("A")), "Read A", "Private A content")
        session.remember(session.snapshot(listOf("B")), "Read B", "B content")
        val scope = session.snapshot(listOf("B"))
        assertEquals(listOf(ConversationTurn("Read B", "B content")), scope.history)
    }

    @Test fun aLateCompletionCannotReintroduceAClearedConversation() {
        val session = ConversationSession()
        session.attach("A")
        val oldRequest = session.snapshot()
        assertTrue(session.isCurrent(oldRequest))
        session.clear()
        session.attach("B")
        assertFalse(session.isCurrent(oldRequest))
        assertFalse(session.remember(oldRequest, "Read A", "Old private A answer"))
        assertEquals(listOf("B"), session.documentIds())
        assertTrue(session.snapshot().history.isEmpty())
    }

    @Test fun anImportStartedInAClearedConversationCannotAttachToTheNewChat() {
        val session = ConversationSession()
        val oldImport = session.snapshot(emptyList())
        session.clear()
        session.attach("B")
        assertFalse(session.attachIfCurrent("A", oldImport.generation))
        assertEquals(listOf("B"), session.documentIds())
        assertTrue(session.snapshot().history.isEmpty())
    }

    @Test fun detachingAAlsoRemovesItsHistoryWithoutChangingB() {
        val session = ConversationSession()
        session.attach("A")
        session.attach("B")
        session.remember(session.snapshot(listOf("A")), "Read A", "A content")
        session.remember(session.snapshot(listOf("B")), "Read B", "B content")
        assertTrue(session.detach("A"))
        assertFalse(session.detach("A"))
        assertEquals(listOf("B"), session.documentIds())
        assertEquals(listOf(ConversationTurn("Read B", "B content")), session.snapshot().history)
    }

    @Test fun requestSnapshotsRemainStableWhenTheSessionChanges() {
        val session = ConversationSession()
        session.attach("A")
        val snapshot = session.snapshot()
        session.attach("B")
        session.clear()
        assertEquals(listOf("A"), snapshot.documentIds)
        assertTrue(session.documentIds().isEmpty())
    }

    @Test fun attachmentLimitsAndIdentifiersAreCheckedBeforeAddingContext() {
        val session = ConversationSession(maxAttachments = 2)
        session.attach("A")
        session.attach("A")
        session.attach("B")
        assertEquals(listOf("A", "B"), session.documentIds())
        assertThrows(IllegalArgumentException::class.java) { session.attach("C") }
        assertThrows(IllegalArgumentException::class.java) { session.attach("../outside") }
        assertEquals(listOf("B", "A"), session.snapshot(listOf("B", "A", "B")).documentIds)
    }

    @Test fun conversationHistoryHasBoundedTurnCountAndTextLengths() {
        val session = ConversationSession(maxHistoryTurns = 2)
        repeat(3) { index ->
            assertTrue(session.remember(session.snapshot(), "$index".repeat(2_000), "$index".repeat(4_000)))
        }
        val history = session.snapshot().history
        assertEquals(2, history.size)
        assertTrue(history.first().question.startsWith("1"))
        assertEquals(1_000, history.last().question.length)
        assertEquals(2_000, history.last().answer.length)
        session.clear()
        assertTrue(session.snapshot().history.isEmpty())
    }
}
