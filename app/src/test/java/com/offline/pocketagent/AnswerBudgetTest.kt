package com.offline.pocketagent

import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnswerBudgetTest {
    @Test fun detailedGeneralAnswerUsesLargerBudgetWithoutClippingAtOldEightThousandCharacters() = runBlocking {
        val answer = "Detailed explanation. ".repeat(450)
        val result = AgentEngine(
            generate = { _, _ -> error("Use budget-aware adapter") },
            streamGenerate = { prompt, _, maximum, _ ->
                assertEquals(1536, maximum)
                assertFalse(prompt.contains("Keep replies brief"))
                answer
            },
        ).respond("Give a detailed explanation of photosynthesis", emptyList(), false)
        assertTrue(answer.length > 8_000)
        assertEquals(answer.trim(), result.text)
    }

    @Test fun explicitRangeCanBeReturnedAsATableWithoutCallingTheModel() = runBlocking {
        val result = AgentEngine(generate = { _, _ -> error("Exact arithmetic needs no model") }).respond(
            "Sum Sales B2:B3 in a table", listOf(LibraryDocument("book1", ExtractedDocument(
                "Sales.xlsx", "xlsx", listOf(
                    DocumentSection("'Sales'!B2", "0.1", "cell", "0.1"),
                    DocumentSection("'Sales'!B3", "0.2", "cell", "0.2"),
                ),
            ))), false,
        )
        assertTrue(result.text.startsWith("| Calculation | Result |"))
        assertTrue(result.text.contains("Sum = 0.3"))
    }
}
