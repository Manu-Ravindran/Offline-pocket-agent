package com.offline.pocketagent

import org.junit.Assert.*
import org.junit.Test

class RequestTimingTest {
    @Test fun distinguishesQueuePreparationProcessingFirstReplyAndTotalLatency() {
        var now = 0L
        val timing = RequestTiming(42) { now }
        now = 50_000_000L; timing.acquired()
        now = 80_000_000L; timing.processing()
        now = 130_000_000L; timing.outputDelivered(10)
        now = 160_000_000L; timing.outputDelivered(20)
        now = 180_000_000L
        val fields = timing.fields(outputChars = 25)
        assertEquals("42", fields["request_id"])
        assertEquals("50", fields["queue_wait_ms"])
        assertEquals("30", fields["preparation_ms"])
        assertEquals("100", fields["processing_ms"])
        assertEquals("130", fields["first_reply_ms"])
        assertEquals("180", fields["duration_ms"])
        assertEquals("25", fields["output_chars"])
        assertEquals("138.889", fields["chars_per_second"])
    }

    @Test fun cancellationWhileQueuedDoesNotInventPreparationOrFirstReply() {
        var now = 0L
        val timing = RequestTiming(7) { now }
        now = 25_000_000L
        val fields = timing.fields()
        assertEquals("25", fields["queue_wait_ms"])
        assertEquals("25", fields["duration_ms"])
        assertEquals("0", fields["output_chars"])
        assertFalse(fields.containsKey("preparation_ms"))
        assertFalse(fields.containsKey("first_reply_ms"))
    }

    @Test fun emptyCallbacksDoNotSetFirstReplyAndFailureRetainsDeliveredCharacterCount() {
        var now = 0L
        val timing = RequestTiming(9) { now }
        timing.acquired(); timing.processing()
        now = 10_000_000L; assertFalse(timing.outputDelivered(0))
        now = 20_000_000L; assertTrue(timing.outputDelivered(5))
        now = 25_000_000L; assertFalse(timing.outputDelivered(5))
        now = 30_000_000L
        assertEquals("20", timing.fields()["first_reply_ms"])
        assertEquals("5", timing.fields()["output_chars"])
    }
}
