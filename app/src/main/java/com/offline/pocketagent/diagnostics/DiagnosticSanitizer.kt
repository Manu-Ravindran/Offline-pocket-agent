package com.offline.pocketagent.diagnostics

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** Logs are built from fixed technical tokens, never from free-form app/user payloads. */
internal object DiagnosticSanitizer {
    private val stages = setOf(
        "app_start", "model_import_start", "model_import_complete", "model_import_failed",
        "model_load_start", "model_load_complete", "model_load_failed", "model_operation_failed",
        "vision_import_start", "vision_import_complete", "vision_import_failed",
        "document_import_start", "document_import_complete", "document_import_failed",
        "request_start", "request_complete", "request_failed", "request_cancelled",
        "request_first_reply", "inference_complete", "inference_cancelled", "inference_failed",
        "conversation_cleared", "documents_cleared", "diagnostics_collected", "diagnostics_cleared",
        "native_model_load_start", "native_model_load_complete", "native_vision_capability",
        "inference_stage_start", "inference_stage_end", "inference_stage_fail",
    )
    private val phases = setOf(
        "sdk_initialization", "text_model_initialization", "multimodal_model_initialization",
        "text_template_initialization", "multimodal_template_initialization",
        "text_chat_template_initialization", "multimodal_chat_template_initialization",
        "text_conversation_reset", "multimodal_conversation_reset", "multimodal_context_reset",
        "text_template", "image_template", "text_chat_template", "image_chat_template",
        "text_generation", "image_generation", "model_load",
    )
    private val operations = setOf(
        "startup", "model", "vision", "document", "request", "summary", "import", "load",
        "import_model", "load_model", "add_vision", "import_document", "ask", "cancel", "reset",
        "new_conversation", "export_diagnostics", "general", "configure_model", "configure_vision",
        "warmup", "warm_up", "saved_model", "full_pair", "projector_only",
    )
    private val results = setOf("started", "success", "failed", "cancelled", "ready", "unavailable", "none", "complete", "incomplete")
    private val booleans = setOf("text_ready", "vision_ready", "model_saved", "projector_saved", "projector_requested", "vision_available", "vision_requested", "extraction_complete")
    private val numbers = setOf(
        "count", "duration_ms", "context_tokens", "output_tokens", "bytes", "request_id", "inference_id",
        "queue_wait_ms", "preparation_ms", "processing_ms", "first_reply_ms", "first_token_ms", "model_load_ms",
        "output_chars", "generated_tokens", "threads", "batch_tokens", "ubatch_tokens",
        "section_count", "extracted_chars", "total_pages", "processed_pages", "warning_count",
        "document_count", "image_count", "session_scope_count",
    )
    private val rates = setOf("chars_per_second", "tokens_per_second")

    fun event(stage: String, fields: Map<String, String>): String = buildString {
        append(if (stage in stages) stage else "unclassified_event")
        for ((key, raw) in fields.entries.sortedBy { it.key }.take(16)) {
            val value = when {
                key in booleans && raw in setOf("true", "false") -> raw
                key in numbers && raw.matches(Regex("[0-9]{1,19}")) && raw.toLongOrNull() != null -> raw
                key == "code" && raw.matches(Regex("-?[0-9]{1,12}")) -> raw
                key in rates && raw.matches(Regex("[0-9]{1,10}(?:\\.[0-9]{1,4})?")) -> raw
                key == "phase" && normalize(raw) in phases -> normalize(raw)
                key == "operation" && normalize(raw) in operations -> normalize(raw)
                key == "result" && raw in results -> raw
                key == "kind" && raw in setOf("pdf", "docx", "xlsx", "image") -> raw
                else -> null
            }
            if (value != null) append(' ').append(key).append('=').append(value)
        }
    }

    fun failure(stage: String, error: Throwable): String = buildString {
        append(event(stage, emptyMap()))
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 3 && seen.add(current)) {
            val actual = current
            append(if (depth == 0) " error=" else " caused_by=").append(symbol(actual.javaClass.name))
            // Messages can contain a prompt, filename, URI, or a document excerpt.
            // Only fixed diagnostic categories derived from them may be retained.
            val message = runCatching { actual.message?.take(2_048)?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
            val tags = buildList {
                if ("out of memory" in message || actual is OutOfMemoryError) add("memory")
                if ("context" in message && ("limit" in message || "length" in message || "window" in message)) add("context_limit")
                if ("projector" in message || "mmproj" in message) add("vision_projector")
                if ("timed out" in message || "timeout" in message) add("timeout")
                if ("cancel" in message) add("cancelled")
            }
            if (tags.isNotEmpty()) append(" tags=").append(tags.joinToString(","))
            for (frame in actual.stackTrace.take(8)) {
                append(" at=").append(symbol(frame.className)).append('.').append(symbol(frame.methodName))
                if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
            }
            current = runCatching { actual.cause }.getOrNull()
            depth++
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(' ', '_')
    private fun symbol(value: String): String = value.take(180).replace(Regex("[^A-Za-z0-9_.$<>]"), "_")
}
