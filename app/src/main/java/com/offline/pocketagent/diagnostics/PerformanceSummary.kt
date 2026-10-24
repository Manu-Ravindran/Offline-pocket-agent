package com.offline.pocketagent.diagnostics

/** Reads only approved technical metrics from events; never renders arbitrary field values. */
internal object PerformanceSummary {
    private val fieldPattern = Regex("(?:^|\\s)([a-z_]+)=([^\\s]+)")
    private data class Entry(val stage: String, val values: Map<String, String>)
    private data class RequestKey(val launch: Int, val id: String)
    private data class Request(var state: String, val values: MutableMap<String, String>)

    fun render(log: String): String {
        val entries = log.lineSequence().takeLastBounded(256).mapNotNull { line ->
            val stage = line.trim().split(Regex("\\s+"), limit = 3).getOrNull(1) ?: return@mapNotNull null
            // Revalidate when rendering as well: a corrupt or manually changed file cannot add payloads.
            val sanitized = DiagnosticSanitizer.event(stage, fieldPattern.findAll(line)
                .associate { it.groupValues[1] to it.groupValues[2] })
            val values = fieldPattern.findAll(sanitized).associate { it.groupValues[1] to it.groupValues[2] }
            Entry(sanitized.substringBefore(' '), values)
        }.toList()
        return buildString {
            append("Performance measurements\n")
            append("Times are milliseconds (ms). First reply available = request entry to first user-facing answer callback; it excludes progress labels and does not measure screen drawing.\n")
            append("Native first token = inference-call start to first nonempty decoded model token. It includes worker scheduling, template/reset, image encoding, and prompt processing.\n")
            append("Character counts use Unicode code points. Rates are whole-operation averages, including preparation/prompt work; they are not decode-only speeds.\n")
            append("Token counts/rates appear only when the runtime supplies its generated-token count; callback chunks are never counted as tokens.\n\n")
            val requests = linkedMapOf<RequestKey, Request>()
            var launch = 0
            for (entry in entries) {
                if (entry.stage == "app_start") launch++
                if (entry.stage !in setOf("request_start", "request_first_reply", "request_complete", "request_failed", "request_cancelled")) continue
                val id = entry.values["request_id"] ?: continue
                val request = requests.getOrPut(RequestKey(launch, id)) { Request("completion not recorded", mutableMapOf()) }
                request.values.putAll(entry.values)
                when (entry.stage) {
                    "request_complete" -> request.state = "complete"
                    "request_failed" -> request.state = "failed"
                    "request_cancelled" -> request.state = "cancelled"
                }
            }
            append("Recent user requests\n")
            if (requests.isEmpty()) append("No timed request is recorded yet. Reproduce the slow reply, then collect logs again.\n")
            for ((key, request) in requests.entries.toList().takeLast(5)) {
                append("Request ").append(key.id).append(" (retained launch ").append(key.launch).append("): ").append(request.state).append('\n')
                requestLine(request.values)
            }
            val slowest = requests.entries.filter { it.value.values["duration_ms"]?.toLongOrNull() != null }
                .maxByOrNull { it.value.values["duration_ms"]?.toLongOrNull() ?: -1L }
            if (slowest != null) {
                append("Slowest retained request: ").append(slowest.key.id).append(" (retained launch ").append(slowest.key.launch).append("); total ")
                    .append(slowest.value.values["duration_ms"]).append(" ms (").append(slowest.value.state).append(").\n")
            }
            append("\nRecent native inference calls\n")
            val inference = entries.filter { it.stage in setOf("inference_complete", "inference_cancelled", "inference_failed") }.takeLast(8)
            if (inference.isEmpty()) append("No completed native-call timing is recorded yet.\n")
            for (entry in inference) {
                append(entry.stage.removePrefix("inference_")).append(entry.values["request_id"]?.let { "; request $it" }.orEmpty())
                    .append(entry.values["inference_id"]?.let { "; call $it" }.orEmpty()).append('\n')
                entry.values["vision_requested"]?.let { append("  Requested path: ").append(if (it == "true") "image" else "text").append('\n') }
                metric("Native first token", entry.values["first_token_ms"], "ms")
                metric("Native call total", entry.values["duration_ms"], "ms")
                metric("Output characters", entry.values["output_chars"], "characters")
                metric("Average character rate", entry.values["chars_per_second"], "characters/s")
                metric("Generated tokens (SDK)", entry.values["generated_tokens"], "tokens")
                metric("Average token rate (SDK)", entry.values["tokens_per_second"], "tokens/s")
            }
            append("\nRecent document/model setup timings\n")
            val setup = entries.filter { it.stage in setOf("document_import_complete", "model_import_complete", "vision_import_complete", "model_load_complete", "native_model_load_complete") }.takeLast(6)
            if (setup.isEmpty()) append("No setup timing is recorded yet.\n")
            for (entry in setup) {
                append(entry.stage).append(entry.values["result"]?.let { " ($it)" }.orEmpty()).append('\n')
                entry.values["kind"]?.let { append("  Document type: ").append(it).append('\n') }
                metric("Total", entry.values["duration_ms"], "ms")
                metric("Processing", entry.values["processing_ms"], "ms")
                metric("Model load", entry.values["model_load_ms"], "ms")
                metric("Pages processed", entry.values["processed_pages"], "pages")
                metric("Total pages", entry.values["total_pages"], "pages")
                metric("Extracted characters", entry.values["extracted_chars"], "characters")
                metric("Warnings", entry.values["warning_count"], "warnings")
                entry.values["extraction_complete"]?.let { append("  Extraction complete: ").append(it).append('\n') }
            }
            val stages = entries.filter { it.stage == "inference_stage_end" || it.stage == "inference_stage_fail" }.takeLast(8)
            if (stages.isNotEmpty()) {
                append("\nRecent native stages\n")
                for (entry in stages) {
                    append("  ").append(entry.values["phase"] ?: "unspecified")
                        .append(if (entry.stage == "inference_stage_fail") ": failed" else ": completed")
                    entry.values["duration_ms"]?.let { append(" in ").append(it).append(" ms") }
                    append('\n')
                }
            }
            val configuration = entries.lastOrNull { it.values["context_tokens"] != null || it.values["threads"] != null }
            if (configuration != null) {
                append("\nLast recorded inference configuration\n")
                metric("Context capacity", configuration.values["context_tokens"], "tokens")
                metric("CPU threads", configuration.values["threads"], "threads")
                metric("Prompt batch", configuration.values["batch_tokens"], "tokens")
                metric("Prompt micro-batch", configuration.values["ubatch_tokens"], "tokens")
            }
        }
    }

    private fun StringBuilder.requestLine(values: Map<String, String>) {
        metric("Queue wait", values["queue_wait_ms"], "ms")
        metric("Preparation before answer engine", values["preparation_ms"], "ms")
        metric("Answer engine processing", values["processing_ms"], "ms")
        metric("First reply available", values["first_reply_ms"], "ms")
        metric("Total request", values["duration_ms"], "ms")
        metric("Output characters", values["output_chars"], "characters")
        metric("Average end-to-end character rate", values["chars_per_second"], "characters/s")
    }

    private fun StringBuilder.metric(label: String, value: String?, unit: String) {
        if (value != null) append("  ").append(label).append(": ").append(value).append(' ').append(unit).append('\n')
    }

    private fun Sequence<String>.takeLastBounded(maximum: Int): List<String> {
        val lines = ArrayDeque<String>(maximum)
        for (line in this) {
            if (lines.size == maximum) lines.removeFirst()
            lines.addLast(line)
        }
        return lines.toList()
    }
}
