package com.offline.pocketagent

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Controller-level latency boundaries; no text, file identifiers, or model token guesses are stored. */
internal class RequestTiming(
    val requestId: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val submittedAt = nowNanos()
    private var acquiredAt: Long? = null
    private var processingAt: Long? = null
    private val firstOutputAt = AtomicLong(Long.MIN_VALUE)
    private val lastOutputChars = AtomicInteger(0)

    fun acquired() { if (acquiredAt == null) acquiredAt = nowNanos() }
    fun processing() { if (processingAt == null) processingAt = nowNanos() }

    fun outputDelivered(characterCount: Int): Boolean {
        lastOutputChars.set(characterCount.coerceAtLeast(0))
        return characterCount > 0 && firstOutputAt.compareAndSet(Long.MIN_VALUE, nowNanos())
    }

    fun fields(outputChars: Int = lastOutputChars.get()): Map<String, String> {
        val now = nowNanos()
        val duration = (now - submittedAt).coerceAtLeast(0L)
        val acquired = acquiredAt
        val processing = processingAt
        return linkedMapOf(
            "request_id" to requestId.toString(),
            "queue_wait_ms" to millis((acquired ?: now) - submittedAt),
            "duration_ms" to millis(duration),
            "output_chars" to outputChars.coerceAtLeast(0).toString(),
        ).apply {
            if (acquired != null) put("preparation_ms", millis((processing ?: now) - acquired))
            if (processing != null) put("processing_ms", millis(now - processing))
            val firstOutput = firstOutputAt.get()
            if (firstOutput != Long.MIN_VALUE) put("first_reply_ms", millis(firstOutput - submittedAt))
            if (duration > 0L) put("chars_per_second", String.format(Locale.ROOT, "%.3f", outputChars.coerceAtLeast(0).toDouble() * 1_000_000_000.0 / duration))
        }
    }

    private fun millis(nanos: Long): String = (nanos.coerceAtLeast(0L) / 1_000_000L).toString()
}
