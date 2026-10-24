package com.offline.pocketagent

import org.junit.Assert.*
import org.junit.Test

class SourceCitationsTest {
    private val allowed = setOf("doc1:0", "doc1:image", "book1:Sales!B2:B20")

    @Test fun literalBracketsAndMarkdownLinksRemainUsable() {
        assertTrue(SourceCitations.invalid("Price [USD], array [1, 2, 3], time [12:30], [help](https://example.com).", allowed).isEmpty())
    }

    @Test fun exactObservedReferenceFormsAreAccepted() {
        assertTrue(SourceCitations.invalid("Facts [doc1:0], image [doc1:image], total [book1:Sales!B2:B20].", allowed).isEmpty())
    }

    @Test fun unobservedAndMalformedSourceReferencesAreRejected() {
        for (text in listOf("Claim [doc1:999].", "Claim [invented:99].", "Claim [invented:image].", "Claim [page 99].",
            "Claim [doc1:0", "Claim [invented:99", "Claim doc1:0]", "Claim [book1:Missing!A1:A5]")) {
            assertTrue(text, SourceCitations.invalid(text, allowed).isNotEmpty())
        }
    }

    @Test fun ordinaryGeneralChatDoesNotNeedDocumentEvidence() {
        assertTrue(SourceCitations.invalid("Here is [1, 2, 3] and [USD] in a table.", emptySet()).isEmpty())
        assertFalse(SourceCitations.invalid("Pretend citation [unknown:12]", emptySet()).isEmpty())
    }
}
