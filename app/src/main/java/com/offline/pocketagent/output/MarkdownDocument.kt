package com.offline.pocketagent.output

/** A small, deterministic Markdown reader. It never interprets HTML or opens links. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Raw(val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock {
        val columnCount: Int get() = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
        val expandedCellCount: Long get() = (rows.size.toLong() + 1) * columnCount
        val storedCellCount: Long get() = headers.size.toLong() + rows.sumOf { it.size.toLong() }
        val fitsNativeGrid: Boolean get() = columnCount <= 32 && expandedCellCount <= MarkdownDocument.MAX_GRID_CELLS
    }
    data object Rule : MarkdownBlock
}

object MarkdownDocument {
    const val MAX_GRID_CELLS = 2_000L
    const val MAX_FORMAT_CHARACTERS = 60_000
    private val heading = Regex("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$")
    private val list = Regex("^(\\s*)([-+*]|[0-9]+[.)])\\s+(.+)$")
    private val separator = Regex(":?-{3,}:?")
    private val rule = Regex("^\\s{0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$")

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.length > MAX_FORMAT_CHARACTERS) return listOf(MarkdownBlock.Raw(markdown))
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) { index++; continue }
            val fence = line.trimStart().takeIf { it.startsWith("```") || it.startsWith("~~~") }
            if (fence != null) {
                val marker = fence.take(3)
                val language = fence.drop(3).trim()
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith(marker)) code += lines[index++]
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(language, code.joinToString("\n"))
                continue
            }
            if (isTableStart(lines, index)) {
                val headers = cells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].isNotBlank() && hasPipe(lines[index])) rows += cells(lines[index++])
                blocks += MarkdownBlock.Table(headers, rows)
                continue
            }
            val title = heading.matchEntire(line.trimStart())
            if (title != null) {
                blocks += MarkdownBlock.Heading(title.groupValues[1].length, title.groupValues[2])
                index++; continue
            }
            if (rule.matches(line)) { blocks += MarkdownBlock.Rule; index++; continue }
            val item = list.matchEntire(line)
            if (item != null) {
                val marker = item.groupValues[2].let { if (it in listOf("-", "+", "*")) "•" else it }
                blocks += MarkdownBlock.ListItem(marker, item.groupValues[3], item.groupValues[1].length / 2)
                index++; continue
            }
            val paragraph = mutableListOf(line)
            index++
            while (index < lines.size && lines[index].isNotBlank() && !isTableStart(lines, index) &&
                heading.matchEntire(lines[index].trimStart()) == null && list.matchEntire(lines[index]) == null &&
                !rule.matches(lines[index]) && !lines[index].trimStart().startsWith("```") && !lines[index].trimStart().startsWith("~~~")) {
                paragraph += lines[index++]
            }
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
        }
        return blocks
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size && hasPipe(lines[index]) && hasPipe(lines[index + 1]) &&
            cells(lines[index + 1]).let { it.isNotEmpty() && it.all { cell -> separator.matches(cell.replace(" ", "")) } }

    /** Pipes inside inline code or escaped with a backslash stay in their cell. */
    internal fun cells(line: String): List<String> {
        val source = line.trim()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var inCode = false
        while (index < source.length) {
            val character = source[index]
            when {
                character == '\\' && index + 1 < source.length && source[index + 1] == '|' -> { current.append('|'); index++ }
                character == '`' -> { inCode = !inCode; current.append(character) }
                character == '|' && !inCode -> { result += current.toString().trim(); current.clear() }
                else -> current.append(character)
            }
            index++
        }
        result += current.toString().trim()
        if (source.startsWith('|') && result.firstOrNull().isNullOrEmpty()) result.removeAt(0)
        if (source.endsWith('|') && !source.endsWith("\\|") && result.lastOrNull().isNullOrEmpty()) result.removeAt(result.lastIndex)
        return result
    }

    private fun hasPipe(line: String): Boolean = cells(line).size > 1 || line.trim().let { it.startsWith('|') && it.endsWith('|') }

    fun plainInline(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
}
