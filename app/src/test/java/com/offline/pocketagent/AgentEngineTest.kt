package com.offline.pocketagent

import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentEngineTest {
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
        assertThrows(IllegalArgumentException::class.java) {
            SpreadsheetMath.sum(listOf(DocumentSection("'Sales'!A1", "#DIV/0!", "cell")), "Sales", "A1")
        }
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
        assertEquals(listOf("Contract.docx — body paragraph 2"), result.sources)
        assertEquals("Delivery is Friday [doc1:0].", result.text)
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
}
