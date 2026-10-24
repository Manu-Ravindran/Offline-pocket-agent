package com.offline.pocketagent

import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentEngineTest {
    @Test fun simpleChatUsesOnePlainModelPass() = runBlocking {
        var calls = 0
        val updates = mutableListOf<String>()
        val result = AgentEngine(generate = { prompt, image ->
            calls++
            assertNull(image)
            assertFalse(prompt.contains("Return ONE JSON object"))
            "Hello! How can I help?"
        }).respond("Hello", emptyList(), false, updates::add)
        assertEquals(1, calls)
        assertEquals("Hello! How can I help?", result.text)
        assertEquals(result.text, updates.last())
    }

    @Test fun documentQuestionRetrievesSourcesWithoutPlannerRoundTrips() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { prompt, _ ->
            calls++
            assertTrue(prompt.contains("[doc1:0]"))
            assertTrue(prompt.contains("Delivery is Friday"))
            "Delivery is Friday [doc1:0]."
        }).respond("When is delivery?", deliveryLibrary(), false)
        assertEquals(1, calls)
        assertEquals(listOf("[doc1:0] Contract.docx — body paragraph 2"), result.sources)
    }

    @Test fun answerStreamingReportsCumulativeTextOnly() = runBlocking {
        val updates = mutableListOf<String>()
        var plannerCalls = 0
        val result = AgentEngine(
            generate = { _, _ -> plannerCalls++; error("A planner pass is unnecessary") },
            streamGenerate = { _, _, maximum, onDelta ->
                assertEquals(512, maximum)
                onDelta("Hello")
                onDelta(" there")
                "Hello there"
            },
        ).respond("Hello", emptyList(), false, updates::add)
        assertEquals(0, plannerCalls)
        assertEquals(listOf("Hello", "Hello there", "Hello there"), updates)
        assertEquals("Hello there", result.text)
    }

    @Test fun explicitSpreadsheetRangeIsCalculatedWithoutModelCalls() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ -> calls++; error("Exact ranges need no planner") }).respond(
            "Sum Sales B2:B3", listOf(LibraryDocument("book1", ExtractedDocument("Sales.xlsx", "xlsx", listOf(
                DocumentSection("'Sales'!B2", "0.1", "cell", "0.1"),
                DocumentSection("'Sales'!B3", "0.2", "cell", "0.2"),
            )))), false,
        )
        assertEquals(0, calls)
        assertTrue(result.text.contains("Sum = 0.3"))
        assertEquals(listOf("[book1:Sales!B2:B3] Sales.xlsx — 'Sales'!B2:B3"), result.sources)
    }

    @Test fun visualQuestionWithoutProjectorDoesNotGuessImageContents() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ -> calls++; "A guessed scene" }).respond(
            "Describe this photo", listOf(LibraryDocument("image1", ExtractedDocument(
                "Photo.jpg", "image", emptyList(), imagePath = "/not-opened.jpg",
            ))), false,
        )
        assertEquals(0, calls)
        assertTrue(result.text.contains("matching vision file"))
        assertFalse(result.text.contains("guessed scene"))
    }

    @Test fun onePassAnswersStillRejectInventedSources() = runBlocking {
        val updates = mutableListOf<String>()
        val result = AgentEngine(generate = { _, _ -> "Delivery is Friday [invented:99]." })
            .respond("When is delivery?", deliveryLibrary(), false, updates::add)
        assertFalse(result.text.contains("invented:99"))
        assertTrue(result.text.contains("couldn't verify"))
        assertEquals(result.text, updates.last())
    }

    @Test fun onePassModelCannotExposePlannerJsonAsAChatMessage() = runBlocking {
        val updates = mutableListOf<String>()
        val result = AgentEngine(
            generate = { _, _ -> error("Direct generation should stream") },
            streamGenerate = { _, _, _, onDelta ->
                val raw = """{"action":"execute_shell","command":"anything"}"""
                onDelta(raw)
                raw
            },
        ).respond("Hello", emptyList(), false, updates::add)
        assertFalse(result.text.contains("execute_shell"))
        assertTrue(updates.none { it.contains("execute_shell") })
    }

    @Test fun directPromptBoundsConversationHistoryAndExcerptSize() = runBlocking {
        val veryLong = "delivery " + "x".repeat(100_000)
        val result = AgentEngine(generate = { prompt, _ ->
            assertTrue(prompt.length < 10_000)
            "Some document content is outside the available excerpt [doc1:0]."
        }).respond("What does it say about delivery?", listOf(LibraryDocument("doc1", ExtractedDocument(
            "Contract.docx", "docx", listOf(DocumentSection("body paragraph 1", veryLong, "paragraph")),
        ))), false, history = List(10) { ConversationTurn("question".repeat(1_000), "answer".repeat(1_000)) })
        assertTrue(result.sources.isNotEmpty())
    }

    @Test fun decimalArithmeticExcludesNumericLookingText() {
        val result = SpreadsheetMath.sum(listOf(
            DocumentSection("'Sales'!B2", "0.1", "cell", "0.1"),
            DocumentSection("'Sales'!B3", "0.2", "cell", "0.2"),
            DocumentSection("'Sales'!B4", "100", "cell"),
            DocumentSection("'Other'!B2", "999", "cell", "999"),
        ), "Sales", "B2:B4")
        assertEquals("0.3", result.total.toPlainString())
        assertEquals(2, result.count)
        assertFalse(result.usedCachedFormulas)
    }

    @Test fun formulaCacheIsExplicitlyReported() {
        val result = SpreadsheetMath.sum(listOf(
            DocumentSection("'Sales'!A1", "Formula: =1+2; cached value: 3", "cell", "3", true),
        ), "Sales", "A1")
        assertEquals("3", result.total.toPlainString())
        assertTrue(result.usedCachedFormulas)
    }

    @Test fun missingFormulaCacheCannotBecomeZero() {
        assertThrows(IllegalArgumentException::class.java) {
            SpreadsheetMath.sum(listOf(
                DocumentSection("'Sales'!A1", "Formula: =SUM(B1:B3); cached value: [not available]", "cell", isFormula = true),
            ), "Sales", "A1")
        }
    }

    @Test fun excelErrorCannotDisappearFromTotal() {
        for (error in listOf("#DIV/0!", "#GETTING_DATA", "#CONNECT!", "#BLOCKED!", "#FIELD!", "#UNKNOWN!", "#BUSY!", "#FUTURE_ERROR!")) {
            assertThrows(IllegalArgumentException::class.java) {
                SpreadsheetMath.sum(listOf(
                    DocumentSection("'Sales'!A1", "10", "cell", "10"),
                    DocumentSection("'Sales'!A2", error, "cell"),
                ), "Sales", "A1:A2")
            }
        }
    }

    @Test fun hashtagTextHasAnExplicitConservativeRangeError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SpreadsheetMath.sum(listOf(DocumentSection("'Sales'!A1", "#heading", "cell")), "Sales", "A1")
        }
        assertTrue(error.message.orEmpty().contains("choose a range without this text"))
    }

    @Test fun outOfBoundsAndReversedRangesAreRejected() {
        val cells = listOf(DocumentSection("'Sales'!A1", "1", "cell", "1"))
        for (range in listOf("XFE1", "A1048577", "B2:A1", "A1:Z99999", "A1;DROP")) {
            assertThrows(IllegalArgumentException::class.java) { SpreadsheetMath.sum(cells, "Sales", range) }
        }
    }

    @Test fun searchEvidenceCarriesRealSourceLocator() = runBlocking {
        var calls = 0
        val engine = AgentEngine(generate = { _, image ->
            assertNull(image)
            calls++
            if (calls == 1) """{"action":"search","query":"delivery"}"""
            else """{"action":"answer","text":"Delivery is Friday [doc1:0]."}"""
        })
        val result = engine.answer("When is delivery?", listOf(LibraryDocument("doc1", ExtractedDocument(
            "Contract.docx", "docx", listOf(DocumentSection("body paragraph 2", "Delivery is Friday.", "paragraph")),
        ))), false)
        assertEquals(2, calls)
        assertEquals(listOf("[doc1:0] Contract.docx — body paragraph 2"), result.sources)
        assertEquals("Delivery is Friday [doc1:0].", result.text)
    }

    @Test fun fabricatedCitationIsCorrectedUsingObservedEvidence() = runBlocking {
        var calls = 0
        val engine = AgentEngine(generate = { prompt, _ ->
            calls++
            when (calls) {
                1 -> """{"action":"search","query":"delivery"}"""
                2 -> """{"action":"answer","text":"Delivery is Friday [doc1:999] [page 99]."}"""
                else -> {
                    assertTrue(prompt.contains("Citation error"))
                    assertTrue(prompt.contains("source IDs returned by tools: doc1:0"))
                    """{"action":"answer","text":"Delivery is Friday [doc1:0]."}"""
                }
            }
        })
        val result = engine.answer("When is delivery?", listOf(LibraryDocument("doc1", ExtractedDocument(
            "Contract.docx", "docx", listOf(DocumentSection("body paragraph 2", "Delivery is Friday.", "paragraph")),
        ))), false)
        assertEquals(3, calls)
        assertEquals("Delivery is Friday [doc1:0].", result.text)
        assertTrue(result.steps.contains("Rejected unverified source references."))
        assertEquals(listOf("[doc1:0] Contract.docx — body paragraph 2"), result.sources)
    }

    @Test fun repeatedFabricatedCitationsExhaustBudgetWithoutReturningFabrication() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ ->
            calls++
            """{"action":"answer","text":"Unsupported assertion [invented:123]."}"""
        }).answer("Read a document", emptyList(), false)
        assertEquals(AgentEngine.MAX_TOOL_STEPS + 1, calls)
        assertFalse(result.text.contains("invented:123"))
        assertTrue(result.text.contains("action limit"))
        assertTrue(result.sources.isEmpty())
    }

    @Test fun malformedCitationBracketsCannotBypassValidation() = runBlocking {
        val result = AgentEngine(generate = { _, _ ->
            """{"action":"answer","text":"Unsupported assertion [invented:123"}"""
        }).answer("Read a document", emptyList(), false)
        assertFalse(result.text.contains("invented:123"))
        assertTrue(result.text.contains("action limit"))
    }

    @Test fun unknownToolNeverExecutesAndBudgetTerminates() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ ->
            calls++
            """{"action":"execute_shell","command":"anything"}"""
        }).answer("Read a file", emptyList(), false)
        assertEquals(AgentEngine.MAX_TOOL_STEPS + 1, calls)
        assertTrue(result.text.contains("action limit"))
        assertTrue(result.sources.isEmpty())
    }

    @Test fun imageInspectionRequiresVisionAndNeverCallsImageModelWithoutIt() = runBlocking {
        var imageCalls = 0
        var calls = 0
        val engine = AgentEngine(generate = { _, image ->
            if (image != null) imageCalls++
            calls++
            if (calls == 1) """{"action":"inspect_image","document_id":"image1"}"""
            else """{"action":"answer","text":"Only OCR text is available."}"""
        })
        engine.answer("What is in the photo?", listOf(LibraryDocument("image1", ExtractedDocument(
            "Photo.jpg", "image", emptyList(), imagePath = "/nonexistent.jpg",
        ))), false)
        assertEquals(0, imageCalls)
    }

    @Test fun jsonProtocolRejectsTrailingCommandsAndHandlesThinkingWrapper() {
        assertNull(AgentEngine.parseAction("""{"action":"answer"}; execute()"""))
        assertNull(AgentEngine.parseAction("not json"))
        assertEquals("answer", AgentEngine.parseAction("<think>analysis</think>\n```json\n{\"action\":\"answer\",\"text\":\"Hello\"}\n```")?.getString("action"))
    }

    private fun deliveryLibrary() = listOf(LibraryDocument("doc1", ExtractedDocument(
        "Contract.docx", "docx", listOf(DocumentSection("body paragraph 2", "Delivery is Friday.", "paragraph")),
    )))
}
