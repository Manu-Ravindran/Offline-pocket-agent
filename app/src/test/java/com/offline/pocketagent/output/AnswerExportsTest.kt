package com.offline.pocketagent.output

import org.junit.Assert.*
import org.junit.Test

class AnswerExportsTest {
    @Test fun parsesHeadingsListsCodeAndTableWithoutExecutingHtml() {
        val blocks = MarkdownDocument.parse("# Results\n\n- First\n\n```html\n<script>alert(1)</script>\n```\n\n| Name | Total |\n| --- | ---: |\n| Alpha | 42 |")
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.ListItem)
        assertEquals("<script>alert(1)</script>", (blocks[2] as MarkdownBlock.Code).text)
        assertEquals(listOf("Alpha", "42"), (blocks[3] as MarkdownBlock.Table).rows.single())
    }

    @Test fun escapedAndCodePipesRemainInsideTableCells() {
        val table = MarkdownDocument.parse("| A | B |\n| --- | --- |\n| left \\| right | `a|b` |").single() as MarkdownBlock.Table
        assertEquals(listOf("left | right", "`a|b`"), table.rows.single())
    }

    @Test fun tableExportPreservesLongValuesExtraColumnsAndAllRows() {
        val long = "A".repeat(1_200)
        val table = MarkdownDocument.parse("| Key | Value |\n| --- | --- |\n| First | $long | Extra |\n| Second | Last |").single() as MarkdownBlock.Table
        assertEquals(3, table.columnCount)
        assertTrue(AnswerExports.csv(table).contains(long))
        assertTrue(AnswerExports.csv(table).contains("\"Extra\""))
        assertTrue(AnswerExports.tableTsv(table).contains("Second\tLast"))
    }

    @Test fun csvQuotesDelimitersAndNeutralizesSpreadsheetFormulas() {
        val table = MarkdownBlock.Table(listOf("Name", "Value"), listOf(
            listOf("a,\"b\"", "=HYPERLINK(\"https://example.test\")"),
            listOf("+cmd", "  @SUM(1,2)"), listOf("-1", "safe\ntext"),
        ))
        val csv = AnswerExports.csv(table)
        assertTrue(csv.startsWith("\uFEFF\"Name\",\"Value\"\r\n"))
        assertTrue(csv.contains("\"a,\"\"b\"\"\""))
        assertTrue(csv.contains("\"'=HYPERLINK"))
        assertTrue(csv.contains("\"'+cmd\""))
        assertTrue(csv.contains("\"'  @SUM"))
        assertTrue(csv.contains("\"'-1\""))
        assertTrue(csv.contains("\"safe\ntext\""))
        assertTrue(AnswerExports.tableTsv(table).contains("'=HYPERLINK"))
    }

    @Test fun exportedHtmlEscapesModelContentAndHasNoExecutableLinks() {
        val html = AnswerExports.html("# <script>alert('x')</script>\n\n| A | B |\n| --- | --- |\n| <img src=x onerror=alert(1)> | & value |", "<title>")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img "))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("default-src 'none'"))
    }

    @Test fun filenamesCannotEscapeChosenExportDirectory() {
        assertEquals("etc-passwd", AnswerExports.safeName("../../etc/passwd"))
        assertEquals("pocket-agent-answer", AnswerExports.safeName("../"))
        assertTrue(AnswerExports.safeName("A".repeat(100)).length <= 64)
        assertFalse(AnswerExports.safeName("x\\y/z").contains('/'))
    }

    @Test fun exportFormatsIncludeSeparateCsvForEveryTable() {
        val markdown = "| A |\n| --- |\n| One |\n\nText\n\n| B |\n| --- |\n| Two |"
        val exports = AnswerExports.options(markdown)
        assertEquals(5, exports.size)
        assertEquals(2, exports.count { it.mimeType == "text/csv" })
        assertEquals(2, exports.filter { it.mimeType == "text/csv" }.map { it.fileName }.toSet().size)
    }

    @Test fun raggedWideTableNeverExpandsIntoDenseMillionCellExports() {
        val header = (1..1_000).joinToString(" | ") { "H$it" }
        val separator = (1..1_000).joinToString(" | ") { "---" }
        val rows = (1..1_000).joinToString("\n") { "| row$it |" }
        val markdown = "| $header |\n| $separator |\n$rows"
        val table = MarkdownDocument.parse(markdown).single() as MarkdownBlock.Table
        assertTrue(table.expandedCellCount > 1_000_000)
        assertFalse(table.fitsNativeGrid)
        val tsv = AnswerExports.tableTsv(table)
        val csv = AnswerExports.csv(table)
        val html = AnswerExports.html(markdown)
        assertTrue(tsv.length < markdown.length * 2)
        assertTrue(csv.length < markdown.length * 2)
        assertTrue(html.length < markdown.length * 2)
        assertTrue(html.contains("<pre>"))
        assertFalse(html.contains("<table>"))
        assertTrue(AnswerExports.plainText(markdown).contains("row1000"))
    }

    @Test fun oversizedMarkupUsesCompleteRawTextAndKeepsMarkdownDownload() {
        val markdown = "**full output**\n".repeat(10_000)
        assertEquals(markdown, (MarkdownDocument.parse(markdown).single() as MarkdownBlock.Raw).text)
        assertEquals(markdown, AnswerExports.plainText(markdown))
        val exports = AnswerExports.options(markdown)
        assertEquals(markdown, exports.first { it.mimeType == "text/markdown" }.content)
        assertEquals(markdown, exports.first { it.mimeType == "text/plain" }.content)
        assertFalse(exports.any { it.mimeType == "text/html" })
    }

    @Test fun savePickerCannotReturnFilePathsOrOurOwnProvider() {
        val own = "com.offline.pocketagent.files"
        ExportDestinationPolicy.requireExternalContent("content", "com.android.externalstorage.documents", own)
        listOf("file" to null, "content" to "", "content" to own, "content" to "10@$own").forEach { (scheme, authority) ->
            try {
                ExportDestinationPolicy.requireExternalContent(scheme, authority, own)
                fail("Unsafe destination was accepted: $scheme $authority")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
