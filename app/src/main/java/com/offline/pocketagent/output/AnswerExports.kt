package com.offline.pocketagent.output

import java.text.Normalizer

data class AnswerExport(val label: String, val fileName: String, val mimeType: String, val content: String)

/** Portable UTF-8 exports built from data, never model-provided paths or executable HTML. */
object AnswerExports {
    const val MAX_HTML_BYTES = 1_000_000
    private const val MAX_HTML_INPUT_CHARACTERS = 150_000
    private const val MAX_CSV_CELLS = 10_000L
    fun options(markdown: String, title: String = "pocket-agent-answer"): List<AnswerExport> {
        val name = safeName(title)
        val blocks = MarkdownDocument.parse(markdown)
        val tables = blocks.filterIsInstance<MarkdownBlock.Table>()
        return buildList {
            add(AnswerExport("Markdown (.md)", "$name.md", "text/markdown", markdown))
            add(AnswerExport("Plain text (.txt)", "$name.txt", "text/plain", plainText(blocks)))
            if (markdown.length <= MAX_HTML_INPUT_CHARACTERS) {
                add(AnswerExport("Formatted document (.html)", "$name.html", "text/html", html(markdown, title)))
            }
            tables.forEachIndexed { index, table ->
                if (table.storedCellCount <= MAX_CSV_CELLS) {
                    add(AnswerExport(if (tables.size == 1) "Table (.csv)" else "Table ${index + 1} (.csv)",
                        "$name-table-${index + 1}.csv", "text/csv", csv(table)))
                }
            }
        }
    }

    fun plainText(markdown: String): String = plainText(MarkdownDocument.parse(markdown))

    private fun plainText(blocks: List<MarkdownBlock>): String = blocks.joinToString("\n\n") { block -> when (block) {
        is MarkdownBlock.Heading -> MarkdownDocument.plainInline(block.text)
        is MarkdownBlock.Paragraph -> MarkdownDocument.plainInline(block.text)
        is MarkdownBlock.ListItem -> "${"  ".repeat(block.indent.coerceAtMost(12))}${block.marker} ${MarkdownDocument.plainInline(block.text)}"
        is MarkdownBlock.Code -> block.text
        is MarkdownBlock.Raw -> block.text
        is MarkdownBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { row ->
            row.joinToString("\t") { MarkdownDocument.plainInline(it) }
        }
        MarkdownBlock.Rule -> "────────────────"
    } }

    fun tableTsv(table: MarkdownBlock.Table): String = (listOf(table.headers) + table.rows).joinToString("\n") { row ->
        row.joinToString("\t") {
            spreadsheetSafe(MarkdownDocument.plainInline(it).replace('\t', ' ').replace('\r', ' ').replace('\n', ' '))
        }
    }

    fun csv(table: MarkdownBlock.Table): String {
        require(table.storedCellCount <= MAX_CSV_CELLS) { "This table is too large for CSV export. Download Markdown or plain text to keep the full answer." }
        return "\uFEFF" + (listOf(table.headers) + table.rows).joinToString("\r\n", postfix = "\r\n") { row ->
            row.joinToString(",") {
                val cell = spreadsheetSafe(MarkdownDocument.plainInline(it))
                "\"${cell.replace("\"", "\"\"")}\""
            }
        }
    }

    /** Quoting CSV alone does not prevent spreadsheet programs executing formulas. */
    internal fun spreadsheetSafe(value: String): String {
        val first = value.trimStart { it.isWhitespace() || it.code <= 0x20 || it == '\uFEFF' }.firstOrNull()
        return if (first != null && first in "=+-@") "'$value" else value
    }

    fun safeName(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .replace(Regex("[/\\\\]+"), "-")
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .replace(Regex("[ _-]+"), "-").trim('-').take(64).ifBlank { "pocket-agent-answer" }

    internal fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun html(markdown: String, title: String = "Pocket Agent answer"): String {
        require(markdown.length <= MAX_HTML_INPUT_CHARACTERS) { "This answer is too large for HTML export. Download Markdown or plain text to keep the full answer." }
        fun inline(text: String) = escapeHtml(MarkdownDocument.plainInline(text)).replace("\n", "<br>")
        val blocks = MarkdownDocument.parse(markdown)
        val fallback = blocks.any { it is MarkdownBlock.Raw || (it is MarkdownBlock.Table && !it.fitsNativeGrid) } ||
            blocks.filterIsInstance<MarkdownBlock.Table>().sumOf { it.expandedCellCount } > MarkdownDocument.MAX_GRID_CELLS
        val body = if (fallback) "<pre>${escapeHtml(markdown)}</pre>" else blocks.joinToString("\n") { block -> when (block) {
            is MarkdownBlock.Heading -> "<h${block.level}>${inline(block.text)}</h${block.level}>"
            is MarkdownBlock.Paragraph -> "<p>${inline(block.text)}</p>"
            is MarkdownBlock.ListItem -> "<p class=\"list\">${escapeHtml(block.marker)} ${inline(block.text)}</p>"
            is MarkdownBlock.Code -> "<pre><code>${escapeHtml(block.text)}</code></pre>"
            is MarkdownBlock.Raw -> "<pre>${escapeHtml(block.text)}</pre>"
            is MarkdownBlock.Table -> buildString {
                append("<div class=\"table\"><table><thead><tr>")
                repeat(block.columnCount) { append("<th>").append(inline(block.headers.getOrElse(it) { "" })).append("</th>") }
                append("</tr></thead><tbody>")
                block.rows.forEach { row ->
                    append("<tr>")
                    repeat(block.columnCount) { append("<td>").append(inline(row.getOrElse(it) { "" })).append("</td>") }
                    append("</tr>")
                }
                append("</tbody></table></div>")
            }
            MarkdownBlock.Rule -> "<hr>"
        } }
        val document = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">" +
            "<title>${escapeHtml(title.take(200))}</title><style>body{font:16px/1.6 system-ui,sans-serif;color:#172a3c;max-width:960px;margin:32px auto;padding:0 20px}" +
            "h1,h2,h3{line-height:1.25}table{border-collapse:collapse;width:100%;font-size:14px}th,td{border:1px solid #dbe5e8;padding:10px;text-align:left;vertical-align:top}" +
            "th{background:#eef5f3}.table{overflow:auto}pre{white-space:pre-wrap;background:#f4f7f9;padding:16px;border-radius:8px}.list{margin:6px 0}</style></head><body>$body</body></html>"
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_HTML_BYTES) { "This answer is too large for HTML export. Download Markdown or plain text instead." }
        return document
    }
}
