package com.offline.pocketagent.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test fun unknownStagesAndPayloadFieldsCannotLeakUserContent() {
        val result = DiagnosticSanitizer.event("secret medical document", mapOf(
            "prompt" to "Patient Alice has condition X", "uri" to "content://private/secret.pdf",
            "filename" to "Alice-report.pdf", "phase" to "Alice-report.pdf", "count" to "2",
        ))
        assertEquals("unclassified_event count=2", result)
    }

    @Test fun onlyTechnicalEnumsAndNumericBooleanFieldsAreRecorded() {
        val result = DiagnosticSanitizer.event("inference_stage_start", mapOf(
            "phase" to "Image generation", "vision_available" to "true", "duration_ms" to "50",
            "operation" to "load_model", "code" to "/data/private", "result" to "secret phrase",
        ))
        assertTrue(result.contains("phase=image_generation"))
        assertTrue(result.contains("vision_available=true"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("secret"))
    }

    @Test fun exceptionMessagesPathsAndExcerptsAreNeverPersisted() {
        val error = IllegalStateException("Projector failed at /storage/emulated/0/Alice.pdf; prompt=confidential contract")
        error.stackTrace = arrayOf(StackTraceElement("com.offline.pocketagent.inference.LocalInference", "generate", "/private/secret.kt", 50))
        val result = DiagnosticSanitizer.failure("inference_stage_fail", error)
        assertTrue(result.contains("java.lang.IllegalStateException"))
        assertTrue(result.contains("vision_projector"))
        assertTrue(result.contains("LocalInference.generate:50"))
        for (private in listOf("Alice", "storage", "confidential", "contract", "secret.kt", "prompt=")) assertFalse(result.contains(private))
    }

    @Test fun stackAndCauseChainsAreBoundedAndCyclesCannotLoop() {
        val a = IllegalStateException("private one")
        val b = IllegalArgumentException("private two")
        a.initCause(b)
        b.initCause(a)
        a.stackTrace = Array(100) { StackTraceElement("com.offline.pocketagent.Controller", "call", "Unknown.kt", it) }
        val result = DiagnosticSanitizer.failure("request_failed", a)
        assertTrue(result.length < 4_000)
        assertEquals(1, Regex("caused_by=").findAll(result).count())
        assertFalse(result.contains("private"))
    }

    @Test fun timingsAndVerifiedRatesAcceptOnlyBoundedUnsignedNumbers() {
        val result = DiagnosticSanitizer.event("inference_complete", mapOf(
            "inference_id" to "42", "duration_ms" to "1030", "first_token_ms" to "350",
            "generated_tokens" to "18", "output_chars" to "61", "chars_per_second" to "59.2233",
            "tokens_per_second" to "17.4757", "vision_requested" to "true",
        ))
        for (expected in listOf("inference_id=42", "duration_ms=1030", "first_token_ms=350", "generated_tokens=18",
            "output_chars=61", "chars_per_second=59.2233", "tokens_per_second=17.4757", "vision_requested=true")) {
            assertTrue(result.contains(expected))
        }
        for (invalid in listOf("-1", "9223372036854775808", "3.4", "5ms", "/private/path", "1\nsecret")) {
            assertEquals("request_complete", DiagnosticSanitizer.event("request_complete", mapOf("duration_ms" to invalid)))
        }
        for (invalid in listOf("NaN", "Infinity", "-2.0", "1e3", "1.12345", "10000000000", "12 secret")) {
            assertEquals("inference_complete", DiagnosticSanitizer.event("inference_complete", mapOf("tokens_per_second" to invalid)))
        }
    }

    @Test fun documentCoverageKeepsOnlyFixedTypesCountsAndCompleteness() {
        val result = DiagnosticSanitizer.event("document_import_complete", mapOf(
            "kind" to "pdf", "total_pages" to "20", "processed_pages" to "20", "section_count" to "20",
            "extracted_chars" to "50000", "extraction_complete" to "true", "warning_count" to "0", "result" to "success",
            "document_name" to "private-contract.pdf", "content" to "secret document text",
        ))
        assertTrue(result.contains("kind=pdf"))
        assertTrue(result.contains("total_pages=20"))
        assertTrue(result.contains("extraction_complete=true"))
        assertTrue(result.contains("result=success"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("secret"))
        assertEquals("document_import_complete", DiagnosticSanitizer.event("document_import_complete", mapOf("kind" to "contract.pdf")))
    }
}
