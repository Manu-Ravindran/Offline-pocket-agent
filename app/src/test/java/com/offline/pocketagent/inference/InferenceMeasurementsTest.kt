package com.offline.pocketagent.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InferenceMeasurementsTest {
    @Test fun firstTextTimingCountsUnicodeCharactersAndActualNativeTokens() {
        var now = 1_000_000_000L
        val metrics = InferenceMeasurements(7, true) { now }
        now += 100_000_000L
        metrics.observeText("")
        now += 150_000_000L
        metrics.observeText("A\uD83D\uDE42")
        now += 250_000_000L
        metrics.observeText("B")
        metrics.observeGeneratedTokens(5)
        now = 3_000_000_000L
        val fields = metrics.fields()
        assertEquals("250", fields["first_token_ms"])
        assertEquals("2000", fields["duration_ms"])
        assertEquals("3", fields["output_chars"])
        assertEquals("1.50", fields["chars_per_second"])
        assertEquals("5", fields["generated_tokens"])
        assertEquals("2.50", fields["tokens_per_second"])
    }

    @Test fun unfinishedCallsDoNotInventFirstTokenOrNativeTokenCount() {
        var now = 0L
        val metrics = InferenceMeasurements(8, false) { now }
        now = 500_000_000L
        val fields = metrics.fields()
        assertEquals("500", fields["duration_ms"])
        assertEquals("0", fields["output_chars"])
        assertFalse(fields.containsKey("first_token_ms"))
        assertFalse(fields.containsKey("generated_tokens"))
        assertFalse(fields.containsKey("tokens_per_second"))
    }

    @Test fun partialFailureOutputHasCharacterMetricsWithoutEstimatedTokens() {
        var now = 0L
        val metrics = InferenceMeasurements(9, false) { now }
        now = 100_000_000L
        metrics.observeText("Partial")
        metrics.observeGeneratedTokens(-1)
        now = 1_000_000_000L
        val fields = metrics.fields()
        assertEquals("7", fields["output_chars"])
        assertEquals("7.00", fields["chars_per_second"])
        assertEquals("100", fields["first_token_ms"])
        assertFalse(fields.containsKey("generated_tokens"))
    }
}
