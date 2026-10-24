package com.offline.pocketagent

import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WholeDocumentSummaryTest {
    private val fact = Regex("PAGE_[0-9]+_FACT")
    private val citation = Regex("\\[doc1:[0-9]+\\]")

    @Test fun twentyPageSummaryReadsEveryPageAndCarriesLastPageIntoFinalAnswer() = runBlocking {
        val prompts = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val engine = AgentEngine(
            generate = { _, _ -> error("Summary must use the budget-aware generation callback") },
            onStatus = statuses::add,
            streamGenerate = { prompt, image, maximum, onDelta ->
                assertNull(image)
                assertTrue(prompt.length <= WholeDocumentSummary.MAX_PROMPT_CHARS)
                prompts += prompt
                val facts = fact.findAll(prompt).map { it.value }.distinct().joinToString("; ")
                val source = citation.find(prompt)?.value.orEmpty()
                if (maximum == 2048) {
                    assertTrue(prompt.contains("PAGE_20_FACT"))
                    val answer = "Detailed summary: $facts $source"
                    onDelta(answer)
                    answer
                } else "Reading notes: $facts $source"
            },
        )
        val result = engine.respond("Summarize this entire document in detail", twentyPages(), false)
        for (page in 1..20) assertTrue(prompts.any { it.contains("PAGE_${page}_FACT") })
        assertTrue(result.text.contains("PAGE_20_FACT"))
        assertTrue(result.text.contains("all 20 pages"))
        assertEquals(20, result.sources.size)
        assertTrue(statuses.any { it.startsWith("Reading document batch") })
    }

    @Test fun longSectionIsSplitWithoutDroppingItsTail() = runBlocking {
        val body = "middle detail ".repeat(2_500) + " CRITICAL_END_FACT"
        val prompts = mutableListOf<String>()
        val result = AgentEngine(
            generate = { prompt, _ ->
                prompts += prompt
                val source = citation.find(prompt)?.value ?: "[doc1:0]"
                if (prompt.contains("CRITICAL_END_FACT")) "The ending contains CRITICAL_END_FACT $source"
                else "The document discusses its middle details $source"
            },
        ).respond("Give a whole document summary", listOf(LibraryDocument("doc1", ExtractedDocument(
            "Long.docx", "docx", listOf(DocumentSection("body paragraph 1", body, "paragraph")),
        ))), false)
        assertTrue(prompts.count { it.contains("READING BATCH") } > 5)
        assertTrue(prompts.last().contains("CRITICAL_END_FACT"))
        assertTrue(result.text.contains("CRITICAL_END_FACT"))
        assertTrue(prompts.all { it.length <= WholeDocumentSummary.MAX_PROMPT_CHARS })
    }

    @Test fun hierarchicalReductionIncludesEveryReadingBatch() = runBlocking {
        val prompts = mutableListOf<String>()
        val dense = listOf(LibraryDocument("doc1", ExtractedDocument(
            "Dense.pdf", "pdf", (1..20).map { page ->
                DocumentSection("page $page", "PAGE_${page}_FACT " + "detail ".repeat(750), "pdf_text")
            }, totalPages = 20, processedPages = 20,
        )))
        val result = AgentEngine(
            generate = { prompt, _ ->
                prompts += prompt
                val facts = fact.findAll(prompt).map { it.value }.distinct().joinToString("; ")
                val source = citation.find(prompt)?.value ?: "[doc1:0]"
                "Notes: $facts $source " + "Additional factual detail. ".repeat(45)
            },
        ).respond("Summarize all pages", dense, false)
        assertTrue(prompts.any { it.contains("NOTES FROM EVERY PRECEDING BATCH") })
        for (page in 1..20) assertTrue(prompts.last().contains("PAGE_${page}_FACT"))
        assertTrue(result.text.contains("PAGE_20_FACT"))
        assertTrue(prompts.all { it.length <= WholeDocumentSummary.MAX_PROMPT_CHARS })
    }

    @Test fun twentyOnePageDocumentDoesNotBecomeAFalselyCompleteSummary() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ -> calls++; "Unverified summary" }).respond(
            "Summarize the entire document", listOf(LibraryDocument("doc1", ExtractedDocument(
                "Long.pdf", "pdf", listOf(DocumentSection("page 1", "First page", "pdf_text")),
                totalPages = 21, processedPages = 21,
            ))), false,
        )
        assertEquals(0, calls)
        assertTrue(result.text.contains("21 pages"))
        assertTrue(result.text.contains("up to 20 pages"))
        assertFalse(result.text.contains("Reviewed"))
    }

    @Test fun incompleteExtractionIsVisibleInTheFinalAnswer() = runBlocking {
        val result = AgentEngine(generate = { _, _ -> "The available section covers delivery [doc1:0]." }).respond(
            "Summarize this document", listOf(LibraryDocument("doc1", ExtractedDocument(
                "Scan.pdf", "pdf", listOf(DocumentSection("page 1", "Delivery", "pdf_ocr")),
                warnings = listOf("No readable text was found on PDF page 2."), totalPages = 20,
                processedPages = 20, extractionComplete = false,
            ))), false,
        )
        assertTrue(result.text.contains("Coverage note"))
        assertTrue(result.text.contains("available extracted text"))
        assertFalse(result.text.contains("all 20 pages"))
    }

    @Test fun legacyTruncationWarningPreventsAFullCoverageClaim() = runBlocking {
        val result = AgentEngine(generate = { _, _ -> "Available facts [doc1:0]." }).respond(
            "Summarize", listOf(LibraryDocument("doc1", ExtractedDocument(
                "Old.docx", "docx", listOf(DocumentSection("body paragraph 1", "facts", "paragraph")),
                warnings = listOf("Text extraction stopped at the 120000-character limit; the document may have additional content."),
            ))), false,
        )
        assertTrue(result.text.contains("Coverage note"))
    }

    @Test fun tableFollowupUsesWholeSourceAndHigherOutputBudget() = runBlocking {
        val prompts = mutableListOf<String>()
        val updates = mutableListOf<String>()
        var finalMaximum = 0
        val result = AgentEngine(
            generate = { _, _ -> error("Use streaming adapter") },
            streamGenerate = { prompt, _, maximum, _ ->
                prompts += prompt
                if (maximum == 2048) {
                    finalMaximum = maximum
                    assertTrue(prompt.contains("Markdown pipe table"))
                    assertTrue(prompt.contains("PAGE_20_FACT"))
                    "| Topic | Details | Sources |\n| --- | --- | --- |\n| Final page | PAGE_20_FACT | ${citation.find(prompt)?.value} |"
                } else {
                    "Notes ${fact.findAll(prompt).map { it.value }.distinct().joinToString()} ${citation.find(prompt)?.value}"
                }
            },
        ).respond("Give it in tabular format", twentyPages(), false, updates::add,
            listOf(ConversationTurn("Summarize", "A short previous answer that omitted the ending")))
        assertEquals(2048, finalMaximum)
        assertTrue(result.text.contains("| Topic | Details | Sources |"))
        assertTrue(result.text.contains("PAGE_20_FACT"))
        assertEquals(result.text, updates.last())
    }

    @Test fun namedSummaryDocumentDoesNotReadAnUnselectedDocument() = runBlocking {
        val prompts = mutableListOf<String>()
        val library = listOf(
            LibraryDocument("doc1", ExtractedDocument("Contract.docx", "docx", listOf(DocumentSection("body paragraph 1", "chosen facts", "paragraph")))),
            LibraryDocument("other", ExtractedDocument("Private.docx", "docx", listOf(DocumentSection("body paragraph 1", "OLD_PRIVATE_SENTINEL", "paragraph")))),
        )
        val result = AgentEngine(generate = { prompt, _ -> prompts += prompt; "Chosen facts [doc1:0]." })
            .respond("Summarize Contract.docx", library, false)
        assertTrue(prompts.none { it.contains("OLD_PRIVATE_SENTINEL") })
        assertEquals(1, result.sources.size)
    }

    @Test fun tableUnitHeadersArePreservedInsteadOfRejectedAsFakeSources() = runBlocking {
        val result = AgentEngine(generate = { prompt, _ ->
            assertTrue(prompt.contains("Markdown pipe table"))
            "| Item | Amount [USD] | Sources |\n| --- | --- | --- |\n| Delivery | 123 | [doc1:0] |"
        }).respond("Summarize this in a table", listOf(LibraryDocument("doc1", ExtractedDocument(
            "Invoice.docx", "docx", listOf(DocumentSection("body paragraph 1", "Delivery costs 123 USD.", "paragraph")),
        ))), false)
        assertTrue(result.text.contains("Amount [USD]"))
        assertFalse(result.text.contains("couldn't verify"))
    }

    @Test fun invalidMapCitationsStopRatherThanBecomeEvidence() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ -> calls++; "Invented claim [outside:999]." })
            .respond("Summarize the entire file", twentyPages(), false)
        assertEquals(2, calls)
        assertTrue(result.text.contains("could not produce a verified"))
        assertFalse(result.text.contains("Invented claim"))
    }

    @Test fun cancellationDuringReadingPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                AgentEngine(generate = { _, _ -> throw CancellationException("Stopped") })
                    .respond("Summarize entire file", twentyPages(), false)
            }
        }
    }

    private fun twentyPages() = listOf(LibraryDocument("doc1", ExtractedDocument(
        "Report.pdf", "pdf", (1..20).map { page ->
            DocumentSection("page $page", "PAGE_${page}_FACT " + "factual detail ".repeat(125), "pdf_text")
        }, totalPages = 20, processedPages = 20,
    )))
}
