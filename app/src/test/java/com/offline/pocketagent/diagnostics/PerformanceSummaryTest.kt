package com.offline.pocketagent.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PerformanceSummaryTest {
    @Test fun combinesRequestMetricsAndReportsSlowestWithClearUnits() {
        val report = PerformanceSummary.render("""
            2026-10-24T12:00:00Z app_start
            2026-10-24T12:00:01Z request_start request_id=1
            2026-10-24T12:00:02Z request_first_reply request_id=1 first_reply_ms=950
            2026-10-24T12:00:03Z request_complete request_id=1 duration_ms=2000 queue_wait_ms=20 preparation_ms=80 processing_ms=1800 output_chars=100 chars_per_second=50.0000
            2026-10-24T12:00:04Z request_start request_id=2
            2026-10-24T12:00:05Z request_cancelled request_id=2 duration_ms=400 output_chars=3
        """.trimIndent())
        assertTrue(report.contains("Request 1 (retained launch 1): complete"))
        assertTrue(report.contains("First reply available: 950 ms"))
        assertTrue(report.contains("Queue wait: 20 ms"))
        assertTrue(report.contains("Answer engine processing: 1800 ms"))
        assertTrue(report.contains("Total request: 2000 ms"))
        assertTrue(report.contains("50.0000 characters/s"))
        assertTrue(report.contains("Slowest retained request: 1 (retained launch 1); total 2000 ms (complete)."))
        assertTrue(report.contains("Request 2 (retained launch 1): cancelled"))
        assertTrue(report.contains("does not measure screen drawing"))
    }

    @Test fun nativeRatesUseOnlyAvailableSdkTokenMetricsAndTextImageMode() {
        val report = PerformanceSummary.render("""
            2026-10-24T12:00:00Z inference_complete inference_id=3 vision_requested=true duration_ms=900 first_token_ms=250 generated_tokens=5 tokens_per_second=5.5555 output_chars=10 chars_per_second=11.1111
            2026-10-24T12:00:01Z inference_cancelled inference_id=4 vision_requested=false duration_ms=300 output_chars=0 chunks=99
        """.trimIndent())
        assertTrue(report.contains("complete; call 3"))
        assertTrue(report.contains("Requested path: image"))
        assertTrue(report.contains("Requested path: text"))
        assertTrue(report.contains("Native first token: 250 ms"))
        assertTrue(report.contains("Generated tokens (SDK): 5 tokens"))
        assertTrue(report.contains("Average token rate (SDK): 5.5555 tokens/s"))
        assertEquals(1, Regex("Generated tokens \\(SDK\\):").findAll(report).count())
        assertFalse(report.contains("99"))
        assertTrue(report.contains("not decode-only speeds"))
    }

    @Test fun requestIdsFromDifferentLaunchesAreNeverMerged() {
        val report = PerformanceSummary.render("""
            2026-10-24T12:00:00Z app_start
            2026-10-24T12:00:01Z request_complete request_id=1 first_reply_ms=100 duration_ms=200
            2026-10-24T12:00:02Z app_start
            2026-10-24T12:00:03Z request_start request_id=1
        """.trimIndent())
        assertTrue(report.contains("Request 1 (retained launch 1): complete"))
        assertTrue(report.contains("Request 1 (retained launch 2): completion not recorded"))
        assertEquals(1, Regex("First reply available: 100 ms").findAll(report).count())
    }

    @Test fun reportRevalidatesCorruptedFieldsAndOmitsPrivatePayloads() {
        val report = PerformanceSummary.render("""
            2026-10-24T12:00:00Z request_complete request_id=2 duration_ms=9223372036854775808 output_chars=secret chars_per_second=NaN prompt=private-contract
            2026-10-24T12:00:01Z document_import_complete kind=private-filename result=private-status total_pages=-5
            2026-10-24T12:00:02Z inference_stage_fail phase=private-name duration_ms=10
        """.trimIndent())
        for (private in listOf("private", "secret", "NaN", "9223372036854775808", "-5")) assertFalse(report.contains(private))
        assertTrue(report.contains("unspecified: failed in 10 ms"))
    }

    @Test fun documentCoverageConfigurationAndNativeStageMeasurementsAreUsefulWithoutContent() {
        val report = PerformanceSummary.render("""
            2026-10-24T12:00:00Z native_model_load_complete context_tokens=4096 threads=6 batch_tokens=512 ubatch_tokens=128 duration_ms=500
            2026-10-24T12:00:01Z document_import_complete kind=pdf duration_ms=1200 processed_pages=20 total_pages=20 extracted_chars=40000 warning_count=0 extraction_complete=true result=success
            2026-10-24T12:00:02Z inference_stage_end phase=image_generation duration_ms=2300
        """.trimIndent())
        assertTrue(report.contains("Context capacity: 4096 tokens"))
        assertTrue(report.contains("CPU threads: 6 threads"))
        assertTrue(report.contains("Prompt micro-batch: 128 tokens"))
        assertTrue(report.contains("Pages processed: 20 pages"))
        assertTrue(report.contains("Extraction complete: true"))
        assertTrue(report.contains("image_generation: completed in 2300 ms"))
    }
}
