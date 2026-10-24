package com.offline.pocketagent.output

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/** Read-only native views. Generated HTML and links are displayed as text, never executed. */
class NativeMarkdownRenderer(private val context: Context) {
    private val ink = Color.rgb(23, 42, 60)
    private val teal = Color.rgb(22, 125, 115)
    private val border = Color.rgb(219, 229, 232)

    fun render(markdown: String, target: LinearLayout) {
        target.removeAllViews()
        val blocks = MarkdownDocument.parse(markdown)
        if (blocks.size > 300) {
            target.addView(text(markdown, 14, inline = false), LinearLayout.LayoutParams(-1, -2))
            return
        }
        var remainingGridCells = MarkdownDocument.MAX_GRID_CELLS
        blocks.forEachIndexed { index, block ->
            val view = when (block) {
                is MarkdownBlock.Heading -> text(block.text, when (block.level) { 1 -> 23; 2 -> 20; 3 -> 18; else -> 16 }, true)
                is MarkdownBlock.Paragraph -> text(block.text)
                is MarkdownBlock.ListItem -> LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(block.indent.coerceAtMost(8) * 12), 0, 0, 0)
                    addView(text(block.marker), LinearLayout.LayoutParams(dp(27), -2))
                    addView(text(block.text), LinearLayout.LayoutParams(0, -2, 1f))
                }
                is MarkdownBlock.Code -> text(block.text, 13, inline = false).apply {
                    typeface = Typeface.MONOSPACE
                    background = boxBackground(Color.rgb(236, 241, 245), false)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                is MarkdownBlock.Raw -> text(block.text, 14, inline = false)
                is MarkdownBlock.Table -> {
                    val allowGrid = block.fitsNativeGrid && block.expandedCellCount <= remainingGridCells
                    if (allowGrid) remainingGridCells -= block.expandedCellCount
                    table(block, allowGrid)
                }
                MarkdownBlock.Rule -> View(context).apply { setBackgroundColor(border); minimumHeight = dp(1) }
            }
            target.addView(view, LinearLayout.LayoutParams(-1, if (block == MarkdownBlock.Rule) dp(1) else -2).apply {
                if (index > 0) topMargin = dp(if (block is MarkdownBlock.ListItem) 7 else 13)
            })
        }
    }

    private fun table(table: MarkdownBlock.Table, allowGrid: Boolean): View {
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(table.headers) + table.rows
        if (!allowGrid) {
            panel.addView(text("Large table — copy or save it for spreadsheet use.", 12).apply { setTextColor(teal) })
            panel.addView(HorizontalScrollView(context).apply {
                addView(text(AnswerExports.tableTsv(table), 13, inline = false).apply {
                    typeface = Typeface.MONOSPACE; setHorizontallyScrolling(true)
                })
            })
            return panel
        }
        panel.addView(text("Swipe to view all columns →", 11).apply {
            setTextColor(teal); setPadding(0, 0, 0, dp(7))
        })
        val widths = (0 until table.columnCount).map { column ->
            val longest = rows.maxOfOrNull { row ->
                MarkdownDocument.plainInline(row.getOrElse(column) { "" }).lineSequence().maxOfOrNull(String::length) ?: 0
            } ?: 0
            dp((longest.coerceAtMost(30) * 7 + 24).coerceIn(108, 234))
        }
        val grid = TableLayout(context).apply { background = boxBackground(Color.WHITE, true) }
        rows.forEachIndexed { rowIndex, row ->
            val rowView = TableRow(context).apply { gravity = Gravity.TOP }
            repeat(table.columnCount) { column ->
                val cell = text(row.getOrElse(column) { "" }, 13, rowIndex == 0).apply {
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    minWidth = widths[column]; maxWidth = widths[column]
                    background = boxBackground(if (rowIndex == 0) Color.rgb(232, 244, 240)
                        else if (rowIndex % 2 == 0) Color.rgb(247, 250, 251) else Color.WHITE, true)
                    contentDescription = "${MarkdownDocument.plainInline(table.headers.getOrElse(column) { "Column ${column + 1}" })}: ${MarkdownDocument.plainInline(row.getOrElse(column) { "" })}"
                }
                rowView.addView(cell, TableRow.LayoutParams(widths[column], -2))
            }
            grid.addView(rowView)
        }
        panel.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            clipToPadding = false
            addView(grid, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun text(value: String, size: Int = 16, bold: Boolean = false, inline: Boolean = true) = TextView(context).apply {
        text = if (inline) styledInline(value) else value
        textSize = size.toFloat()
        setTextColor(ink)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(if (size < 15) 3 else 5).toFloat(), 1f)
        setTextIsSelectable(true)
        autoLinkMask = 0
    }

    private fun styledInline(value: String): CharSequence {
        val result = SpannableStringBuilder()
        val pattern = Regex("\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|`([^`\\n]+)`")
        var cursor = 0
        pattern.findAll(value).forEach { match ->
            result.append(value.substring(cursor, match.range.first))
            val part = match.groups[1] ?: match.groups[2] ?: match.groups[3]!!
            val start = result.length
            result.append(part.value)
            val span = if (match.groups[3] != null) TypefaceSpan("monospace") else StyleSpan(Typeface.BOLD)
            result.setSpan(span, start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = match.range.last + 1
        }
        result.append(value.substring(cursor))
        return result
    }

    private fun boxBackground(fill: Int, stroke: Boolean) = GradientDrawable().apply {
        setColor(fill)
        if (stroke) setStroke(dp(1), border)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
