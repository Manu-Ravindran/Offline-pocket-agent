package com.offline.pocketagent.documents

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.util.zip.ZipFile

data class DocumentSection(
    val locator: String,
    val text: String,
    val kind: String,
    val numericValue: String? = null,
    val isFormula: Boolean = false,
)

data class ExtractedDocument(
    val name: String,
    val kind: String,
    val sections: List<DocumentSection>,
    val warnings: List<String> = emptyList(),
    val imagePath: String? = null,
)

data class ExtractionLimits(
    val maxFileBytes: Long = 50L * 1024 * 1024,
    val maxZipEntryBytes: Int = 20 * 1024 * 1024,
    val maxZipTotalBytes: Long = 100L * 1024 * 1024,
    val maxZipEntries: Int = 2_000,
    val maxPages: Int = 40,
    val maxSheets: Int = 10,
    val maxCells: Int = 10_000,
    val maxTextChars: Int = 120_000,
    val maxImageEdge: Int = 2_048,
) {
    init {
        require(maxFileBytes > 0 && maxZipEntryBytes > 0 && maxZipTotalBytes > 0 && maxZipEntries > 0)
        require(maxPages > 0 && maxSheets > 0 && maxCells > 0 && maxTextChars > 0 && maxImageEdge > 0)
    }
}

/** Reads Office Open XML data without executing formulas, macros, or external links. */
class OfficeParser(private val limits: ExtractionLimits = ExtractionLimits()) {
    fun parseDocx(file: File, displayName: String): ExtractedDocument = ZipFile(file).use { zip ->
        require(file.length() <= limits.maxFileBytes) { "This file exceeds the import size limit." }
        validateArchive(zip)
        require(zip.getEntry("word/document.xml") != null) { "This is not a readable .docx document." }
        val output = SectionCollector(limits.maxTextChars)
        readWordPart(zip, "word/document.xml", "body", output)
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.matches(Regex("word/(header|footer)[0-9]+\\.xml")) }
            .sorted()
            .take(40)
            .forEach { readWordPart(zip, it, it.substringAfterLast('/').substringBefore('.'), output) }
        output.warnings += "Extracted Word text and table contents; page layout, drawings, and embedded objects are not interpreted."
        ExtractedDocument(displayName, "docx", output.sections, output.warnings.distinct())
    }

    fun parseXlsx(file: File, displayName: String): ExtractedDocument = ZipFile(file).use { zip ->
        require(file.length() <= limits.maxFileBytes) { "This file exceeds the import size limit." }
        validateArchive(zip)
        val output = SectionCollector(limits.maxTextChars)
        val relationships = readRelationships(zip)
        val sharedStrings = readSharedStrings(zip)
        val dateStyles = readDateStyles(zip)
        val sheets = readSheets(zip)
        if (sheets.size > limits.maxSheets) {
            output.warnings += "Only the first ${limits.maxSheets} worksheets were read."
        }
        var cellsRead = 0
        var formulasSeen = false
        var limitReached = false
        for ((sheetName, relationshipId) in sheets.take(limits.maxSheets)) {
            if (output.full) break
            if (cellsRead >= limits.maxCells) {
                limitReached = true
                break
            }
            val path = relationships[relationshipId]
            if (path == null || zip.getEntry(path) == null) {
                output.warnings += "Worksheet '$sheetName' could not be read."
                continue
            }
            val parser = xml(zip, path)
            var cellRef: String? = null
            var cellType = ""
            var cellStyle = 0
            var rawValue = ""
            var inlineValue = StringBuilder()
            var formula: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "c" -> {
                            cellRef = attribute(parser, "r") ?: "cell ${cellsRead + 1}"
                            cellType = attribute(parser, "t").orEmpty()
                            cellStyle = attribute(parser, "s")?.toIntOrNull() ?: 0
                            rawValue = ""
                            inlineValue = StringBuilder()
                            formula = null
                        }
                        "v" -> if (cellRef != null) rawValue = parser.nextText()
                        "f" -> if (cellRef != null) formula = parser.nextText()
                        "t" -> if (cellRef != null && cellType == "inlineStr") inlineValue.append(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "c" && cellRef != null) {
                        if (cellsRead >= limits.maxCells) {
                            limitReached = true
                            break
                        }
                        cellsRead++
                        val value = when (cellType) {
                            "s" -> rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }
                                ?: "[unavailable shared string]"
                            "inlineStr" -> inlineValue.toString()
                            "b" -> when (rawValue) { "1" -> "TRUE"; "0" -> "FALSE"; else -> rawValue }
                            else -> rawValue
                        }
                        val text = if (formula != null) {
                            formulasSeen = true
                            "Formula: =${formula}; cached value: ${value.ifBlank { "[not available]" }}"
                        } else value
                        if (text.isNotBlank()) {
                            val number = if (cellStyle !in dateStyles) numericValue(rawValue, cellType) else null
                            output.add("'$sheetName'!$cellRef", text, "cell", number, formula != null)
                        }
                        cellRef = null
                        if (output.full) break
                    }
                }
                parser.next()
            }
        }
        if (limitReached) output.warnings += "Reading stopped at the ${limits.maxCells}-cell limit."
        if (formulasSeen) {
            output.warnings += "Formulas were not calculated. Cached values may be missing or out of date."
        }
        output.warnings += "Extracted worksheet cell data. Numeric values retain their stored form; Excel display formats, charts, and macros are not interpreted."
        ExtractedDocument(displayName, "xlsx", output.sections, output.warnings.distinct())
    }

    private fun readWordPart(zip: ZipFile, path: String, part: String, output: SectionCollector) {
        if (output.full) return
        val parser = xml(zip, path)
        var paragraph = 0
        var table = 0
        var row = 0
        var inTable = false
        var inRow = false
        var paragraphText: StringBuilder? = null
        var cellText: StringBuilder? = null
        var rowCells = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT && !output.full) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "tbl" -> { table++; row = 0; inTable = true }
                    "tr" -> { row++; inRow = true; rowCells = mutableListOf() }
                    "tc" -> cellText = StringBuilder()
                    "p" -> { paragraph++; paragraphText = StringBuilder() }
                    "t" -> paragraphText?.append(parser.nextText())
                    "tab" -> paragraphText?.append('\t')
                    "br", "cr" -> paragraphText?.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "p" -> {
                        val text = paragraphText?.toString()?.trim().orEmpty()
                        if (inTable && inRow) {
                            if (text.isNotEmpty()) {
                                if (cellText?.isNotEmpty() == true) cellText?.append('\n')
                                cellText?.append(text)
                            }
                        } else if (text.isNotEmpty()) output.add("$part paragraph $paragraph", text, "paragraph")
                        paragraphText = null
                    }
                    "tc" -> { rowCells.add(cellText?.toString().orEmpty()); cellText = null }
                    "tr" -> {
                        if (rowCells.any { it.isNotBlank() }) {
                            output.add("$part table $table row $row", rowCells.joinToString(" | "), "table_row")
                        }
                        inRow = false
                    }
                    "tbl" -> inTable = false
                }
            }
            parser.next()
        }
    }

    private fun readSheets(zip: ZipFile): List<Pair<String, String>> {
        require(zip.getEntry("xl/workbook.xml") != null) { "This is not a readable .xlsx workbook." }
        val parser = xml(zip, "xl/workbook.xml")
        val sheets = mutableListOf<Pair<String, String>>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = attribute(parser, "name") ?: "Sheet ${sheets.size + 1}"
                val id = attribute(parser, "id") ?: ""
                sheets += name to id
            }
            parser.next()
        }
        return sheets
    }

    private fun readRelationships(zip: ZipFile): Map<String, String> {
        require(zip.getEntry("xl/_rels/workbook.xml.rels") != null) { "Workbook worksheet references are missing." }
        val parser = xml(zip, "xl/_rels/workbook.xml.rels")
        val output = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = attribute(parser, "Id")
                val target = attribute(parser, "Target")
                val external = attribute(parser, "TargetMode") == "External"
                if (id != null && target != null && !external && !target.contains('\\')) {
                    val resolved = URI("xl/").resolve(target).normalize()
                    val path = resolved.path?.removePrefix("/")
                    if (resolved.scheme == null && path != null && path.startsWith("xl/") && !path.contains("../")) {
                        output[id] = path
                    }
                }
            }
            parser.next()
        }
        return output
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        if (zip.getEntry("xl/sharedStrings.xml") == null) return emptyList()
        val parser = xml(zip, "xl/sharedStrings.xml")
        val strings = mutableListOf<String>()
        var current: StringBuilder? = null
        var phoneticDepth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "rPh" -> phoneticDepth++
                    "t" -> if (phoneticDepth == 0) current?.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "rPh" -> phoneticDepth--
                    "si" -> { strings.add(current?.toString().orEmpty()); current = null }
                }
            }
            parser.next()
        }
        return strings
    }

    private fun readDateStyles(zip: ZipFile): Set<Int> {
        if (zip.getEntry("xl/styles.xml") == null) return emptySet()
        val parser = xml(zip, "xl/styles.xml")
        val dateFormatIds = mutableSetOf<Int>()
        val formatsByStyle = mutableListOf<Int>()
        var inCellFormats = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "numFmt" -> {
                        val id = attribute(parser, "numFmtId")?.toIntOrNull()
                        val code = attribute(parser, "formatCode").orEmpty()
                            .replace(Regex("\"[^\"]*\""), "")
                            .replace(Regex("\\\\.|_.|\\*."), "")
                        val withoutColors = code.replace(Regex("\\[[^\\]]*]"), "")
                        val isDate = Regex("[ymdhs]", RegexOption.IGNORE_CASE).containsMatchIn(withoutColors) ||
                            Regex("\\[[hms]+]", RegexOption.IGNORE_CASE).containsMatchIn(code)
                        if (id != null && isDate) dateFormatIds += id
                    }
                    "cellXfs" -> inCellFormats = true
                    "xf" -> if (inCellFormats) formatsByStyle += attribute(parser, "numFmtId")?.toIntOrNull() ?: 0
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "cellXfs") {
                inCellFormats = false
            }
            parser.next()
        }
        val builtInDateFormats = (14..22).toSet() + (27..36) + (45..47) + (50..58)
        return formatsByStyle.mapIndexedNotNull { style, format ->
            style.takeIf { format in dateFormatIds || format in builtInDateFormats }
        }.toSet()
    }

    private fun numericValue(rawValue: String, cellType: String): String? {
        if (cellType != "" && cellType != "n") return null
        val value = rawValue.trim()
        if (value.isEmpty() || value.length > 1_024) return null
        // Excel uses bounded floating-point values. Avoid unbounded exponent expansion downstream.
        return value.takeIf {
            runCatching {
                val number = BigDecimal(it)
                number.precision() <= 1_000 && number.scale() in -1_000..1_000
            }.getOrDefault(false)
        }
    }

    private fun validateArchive(zip: ZipFile) {
        require(zip.size() <= limits.maxZipEntries) { "This Office document has too many archive entries." }
        var total = 0L
        for (entry in zip.entries().asSequence()) {
            require(entry.size <= limits.maxZipEntryBytes) { "An Office document component exceeds the extraction size limit." }
            if (entry.size > 0) total += entry.size
            require(total <= limits.maxZipTotalBytes) { "This Office document is too large after decompression." }
        }
    }

    private fun xml(zip: ZipFile, path: String): XmlPullParser {
        val entry = requireNotNull(zip.getEntry(path)) { "Missing Office document component: $path" }
        val bytes = zip.getInputStream(entry).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limits.maxZipEntryBytes) { "Office XML exceeds the extraction size limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        // OOXML parts do not require a DTD or external entities. Reject these before parsing.
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = bytes.toString(charset)
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) {
            "Office XML containing document type or entity declarations is unsupported."
        }
        return XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            .newPullParser().apply {
                try {
                    setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                } catch (_: XmlPullParserException) {
                    // kXML implementations may not expose this optional feature, even when
                    // disabling it. DTD and entity declarations were rejected above, before
                    // any parser sees these bytes; unsupported features cannot enable them.
                }
                setInput(ByteArrayInputStream(bytes), null)
            }
    }

    private fun attribute(parser: XmlPullParser, localName: String): String? =
        (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == localName }
            ?.let { parser.getAttributeValue(it) }
}

internal class SectionCollector(private val maxChars: Int) {
    val sections = mutableListOf<DocumentSection>()
    val warnings = mutableListOf<String>()
    private var used = 0
    val full: Boolean get() = used >= maxChars

    fun add(locator: String, text: String, kind: String, numericValue: String? = null, isFormula: Boolean = false) {
        if (text.isBlank()) return
        val remaining = (maxChars - used).coerceAtLeast(0)
        if (remaining == 0) {
            warnTruncated()
            return
        }
        val kept = text.take(remaining)
        sections += DocumentSection(locator, kept, kind, numericValue, isFormula)
        used += kept.length
        if (kept.length < text.length || full) warnTruncated()
    }

    private fun warnTruncated() {
        if (warnings.none { it.startsWith("Text extraction stopped") }) {
            warnings += "Text extraction stopped at the $maxChars-character limit; the document may have additional content."
        }
    }
}
