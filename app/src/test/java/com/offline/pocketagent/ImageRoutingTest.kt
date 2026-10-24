package com.offline.pocketagent

import com.offline.pocketagent.documents.DocumentSection
import com.offline.pocketagent.documents.ExtractedDocument
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImageRoutingTest {
    @Test fun genericQuestionsAndReadingTextUseRealImageInputWhenVisionIsLoaded() = runBlocking {
        val file = File.createTempFile("pocket-image-test", ".jpg")
        try {
            for (question in listOf("Analyze this", "What is this?", "Read the text and explain what it means", "Summarize this image", "Summarize this", "Make a table from this")) {
                var calls = 0
                val result = AgentEngine(generate = { _, image ->
                    calls++
                    assertEquals(file, image)
                    "Visible details [photo:image]."
                }).respond(question, imageLibrary(file), true)
                assertEquals(1, calls)
                assertTrue(result.text.contains("Visible details"))
            }
        } finally { file.delete() }
    }

    @Test fun normalGreetingUsesTextWithoutAttachingAnOldImage() = runBlocking {
        val result = AgentEngine(generate = { _, image -> assertNull(image); "Hello!" })
            .respond("Hello", imageLibrary(File("not-opened")), true)
        assertEquals("Hello!", result.text)
    }

    @Test fun explicitDocumentQuestionIsNotRedirectedToAnImage() = runBlocking {
        val library = listOf(LibraryDocument("doc1", ExtractedDocument(
            "Contract.docx", "docx", listOf(DocumentSection("body paragraph 1", "Delivery is Friday", "paragraph")),
        ))) + imageLibrary(File("not-opened"))
        val result = AgentEngine(generate = { prompt, image ->
            assertNull(image)
            assertTrue(prompt.contains("Delivery is Friday"))
            "Friday [doc1:0]."
        }).respond("When is delivery in Contract.docx?", library, true)
        assertEquals("Friday [doc1:0].", result.text)
    }

    @Test fun unavailableEncoderExplainsItsActualDiagnosticWithoutGuessing() = runBlocking {
        var calls = 0
        val result = AgentEngine(generate = { _, _ -> calls++; "guessed" }).respond(
            "Analyze this", imageLibrary(File("not-opened")), false,
            visionUnavailableReason = "The supplied projector did not activate a vision encoder.",
        )
        assertEquals(0, calls)
        assertTrue(result.text.contains("did not activate"))
        assertFalse(result.text.contains("guessed"))
    }

    @Test fun explicitPdfQuestionUsesPdfEvidenceInAMixedAttachmentConversation() = runBlocking {
        val library = listOf(LibraryDocument("doc1", ExtractedDocument(
            "Report.pdf", "pdf", listOf(DocumentSection("page 1", "Delivery is Friday", "pdf_text")),
            totalPages = 1, processedPages = 1,
        ))) + imageLibrary(File("not-opened"))
        val result = AgentEngine(generate = { prompt, image ->
            assertNull(image)
            assertTrue(prompt.contains("Delivery is Friday"))
            "Friday [doc1:0]."
        }).respond("When is delivery in Report.pdf?", library, true)
        assertEquals("Friday [doc1:0].", result.text)
    }

    @Test fun explicitTranscriptionWithoutVisionIsLabeledAsOcr() = runBlocking {
        val library = listOf(LibraryDocument("photo", ExtractedDocument(
            "Photo.jpg", "image", listOf(DocumentSection("image OCR", "Invoice 123", "image_ocr")), imagePath = "not-opened",
        )))
        val result = AgentEngine(generate = { prompt, image ->
            assertNull(image)
            assertTrue(prompt.contains("OCR text only"))
            "The OCR text says Invoice 123 [photo:0]."
        }).respond("Read the text", library, false)
        assertTrue(result.text.contains("Invoice 123"))
    }

    private fun imageLibrary(file: File) = listOf(LibraryDocument("photo", ExtractedDocument(
        "Photo.jpg", "image", emptyList(), imagePath = file.path, extractionComplete = false,
    )))
}
