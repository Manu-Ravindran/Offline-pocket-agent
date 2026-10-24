package com.offline.pocketagent.documents

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.RandomAccessFile
import java.io.StringReader
import java.math.BigDecimal
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.Locale

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
    val maxXmlNodes: Int = 200_000,
    val maxXmlDepth: Int = 128,
    val maxXmlAttributeChars: Int = 8_192,
    val maxXmlTextChars: Int = 1_000_000,
) {
    init {
        require(maxFileBytes > 0 && maxZipEntryBytes > 0 && maxZipTotalBytes > 0 && maxZipEntries > 0)
        require(maxPages > 0 && maxSheets > 0 && maxCells > 0 && maxTextChars > 0 && maxImageEdge > 0)
        require(maxXmlNodes > 0 && maxXmlDepth > 0 && maxXmlAttributeChars > 0 && maxXmlTextChars > 0)
    }
}

/** Reads Office Open XML data without executing formulas, macros, or external links. */
class OfficeParser(private val limits: ExtractionLimits = ExtractionLimits()) {
    fun parseDocx(file: File, displayName: String): ExtractedDocument = withArchive(file) { zip ->
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

    fun parseXlsx(file: File, displayName: String): ExtractedDocument = withArchive(file) { zip ->
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
            val addresses = mutableSetOf<String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "c" -> {
                            cellRef = validatedCellReference(attribute(parser, "r"))
                            require(addresses.add(cellRef)) { "A worksheet contains duplicate cell addresses. Save a repaired .xlsx copy before importing." }
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

    private fun readWordPart(zip: OfficeArchive, path: String, part: String, output: SectionCollector) {
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

    private fun readSheets(zip: OfficeArchive): List<Pair<String, String>> {
        require(zip.getEntry("xl/workbook.xml") != null) { "This is not a readable .xlsx workbook." }
        val parser = xml(zip, "xl/workbook.xml")
        val sheets = mutableListOf<Pair<String, String>>()
        val names = mutableSetOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = attribute(parser, "name").orEmpty()
                require(name.length in 1..31 && name.none { it.isISOControl() || it in "[]:*?/\\" } &&
                    !name.startsWith('\'') && !name.endsWith('\'')) {
                    "A worksheet has an invalid name. Use an Excel worksheet name of 1–31 characters."
                }
                require(names.add(name.lowercase(Locale.ROOT))) { "The workbook contains duplicate worksheet names." }
                val id = attribute(parser, "id") ?: ""
                sheets += name to id
                if (sheets.size > limits.maxSheets) break
            }
            parser.next()
        }
        return sheets
    }

    private fun validatedCellReference(reference: String?): String {
        val match = reference?.takeIf { it.length <= 10 }?.let { CELL_REFERENCE.matchEntire(it) }
        require(match != null) { "A worksheet contains an invalid or missing A1 cell address. Save an .xlsx copy in Excel before importing." }
        val letters = match.groupValues[1].uppercase(Locale.ROOT)
        val column = letters.fold(0) { value, character -> value * 26 + (character - 'A' + 1) }
        val row = match.groupValues[2].toInt()
        require(column <= 16_384 && row <= 1_048_576) { "A worksheet cell address exceeds Excel's supported range." }
        return "$letters$row"
    }

    private fun readRelationships(zip: OfficeArchive): Map<String, String> {
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
                    if (resolved.scheme == null && resolved.rawAuthority == null && resolved.query == null && resolved.fragment == null &&
                        path != null && path.startsWith("xl/") && !path.contains("../")) {
                        output[id] = path
                    }
                }
            }
            parser.next()
        }
        return output
    }

    private fun readSharedStrings(zip: OfficeArchive): List<String> {
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

    private fun readDateStyles(zip: OfficeArchive): Set<Int> {
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
                        if (id != null && isDateFormat(code)) dateFormatIds += id
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

    /** Linear scan: malformed strings with many unmatched '[' must not trigger regex backtracking. */
    private fun isDateFormat(code: String): Boolean {
        var index = 0
        while (index < code.length) {
            when (val character = code[index].lowercaseChar()) {
                '\\', '_', '*' -> index += 2
                '"' -> {
                    index++
                    while (index < code.length && code[index] != '"') index++
                    index++
                }
                '[' -> {
                    val end = code.indexOf(']', index + 1)
                    if (end < 0) return false
                    val bracket = code.substring(index + 1, end)
                    if (bracket.isNotEmpty() && bracket.all { it.lowercaseChar() in "hms" }) return true
                    index = end + 1
                }
                else -> {
                    if (character in "ymdhs") return true
                    index++
                }
            }
        }
        return false
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
        val names = mutableSetOf<String>()
        for (entry in zip.entries().asSequence()) {
            require(names.add(entry.name)) { "This Office document contains ambiguous duplicate archive entries." }
            require(entry.size in 0L..limits.maxZipEntryBytes.toLong()) { "An Office document component exceeds the extraction size limit." }
            if (entry.size > 0) total += entry.size
            require(total <= limits.maxZipTotalBytes) { "This Office document is too large after decompression." }
        }
    }

    private inline fun <T> withArchive(file: File, block: (OfficeArchive) -> T): T {
        require(file.isFile && file.length() in 1L..limits.maxFileBytes) { "This file exceeds the import size limit or is empty." }
        validateZipEntryCount(file)
        return ZipFile(file).use { block(OfficeArchive(it)) }
    }

    /** Check the small end record before ZipFile allocates its central-directory index. */
    private fun validateZipEntryCount(file: File) {
        val tail = ByteArray(minOf(file.length(), 65_557L).toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - tail.size)
            input.readFully(tail)
        }
        fun shortAt(offset: Int): Int = (tail[offset].toInt() and 0xff) or ((tail[offset + 1].toInt() and 0xff) shl 8)
        fun uintAt(offset: Int): Long = (0..3).fold(0L) { value, index ->
            value or ((tail[offset + index].toLong() and 0xff) shl (8 * index))
        }
        for (offset in tail.size - 22 downTo 0) {
            if (tail[offset] != 0x50.toByte() || tail[offset + 1] != 0x4b.toByte() ||
                tail[offset + 2] != 0x05.toByte() || tail[offset + 3] != 0x06.toByte()) continue
            if (offset + 22 + shortAt(offset + 20) != tail.size) continue
            require(shortAt(offset + 4) == 0 && shortAt(offset + 6) == 0) { "Multi-part Office archives are unsupported." }
            val entries = shortAt(offset + 10)
            require(entries < 65_535 && entries <= limits.maxZipEntries) { "This Office document has too many archive entries." }
            require(shortAt(offset + 8) == entries) { "The Office archive directory is inconsistent." }
            val directorySize = uintAt(offset + 12)
            val directoryStart = uintAt(offset + 16)
            val endRecordPosition = file.length() - tail.size + offset
            require(directoryStart + directorySize <= endRecordPosition) { "The Office archive directory is inconsistent." }
            // Count real central-directory headers too: an attacker can forge the end-record
            // count, so trusting that count alone would still let ZipFile build a huge index.
            RandomAccessFile(file, "r").use { input ->
                input.seek(directoryStart)
                val header = ByteArray(46)
                var counted = 0
                while (input.filePointer < directoryStart + directorySize) {
                    require(++counted <= limits.maxZipEntries) { "This Office document has too many archive entries." }
                    require(directoryStart + directorySize - input.filePointer >= header.size) { "The Office archive directory is inconsistent." }
                    input.readFully(header)
                    require(header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() && header[2] == 0x01.toByte() && header[3] == 0x02.toByte()) {
                        "The Office archive directory is inconsistent."
                    }
                    fun field(position: Int): Int = (header[position].toInt() and 0xff) or ((header[position + 1].toInt() and 0xff) shl 8)
                    val variableBytes = field(28).toLong() + field(30) + field(32)
                    require(field(28) <= 1_024 && input.filePointer + variableBytes <= directoryStart + directorySize) {
                        "The Office archive directory is inconsistent."
                    }
                    input.seek(input.filePointer + variableBytes)
                }
                require(counted == entries && input.filePointer == directoryStart + directorySize) {
                    "The Office archive directory is inconsistent."
                }
            }
            return
        }
        throw IllegalArgumentException("This is not a readable Office ZIP document.")
    }

    private inner class OfficeArchive(private val zip: ZipFile) {
        private var bytesRead = 0L

        init { validateArchive(zip) }

        fun getEntry(path: String): ZipEntry? = zip.getEntry(path)
        fun entries() = zip.entries()
        fun getInputStream(entry: ZipEntry) = zip.getInputStream(entry)
        fun recordRead(count: Int) {
            bytesRead += count
            require(bytesRead <= limits.maxZipTotalBytes) { "Office extraction exceeds the total decompressed read limit." }
        }
    }

    private fun xml(zip: OfficeArchive, path: String): XmlPullParser {
        val entry = requireNotNull(zip.getEntry(path)) { "Missing Office document component: $path" }
        val bytes = zip.getInputStream(entry).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                zip.recordRead(count)
                require(output.size().toLong() + count <= limits.maxZipEntryBytes) { "Office XML exceeds the extraction size limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        // Only decode supported XML encodings, then give the parser characters rather than
        // the original bytes. A declared/sniffed encoding cannot bypass the declaration check.
        require(!(bytes.size >= 4 && (
            bytes.sliceArray(0..3).contentEquals(byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte())) ||
                bytes.sliceArray(0..3).contentEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0)) ||
                bytes.sliceArray(0..3).contentEquals(byteArrayOf(0, 0, 0, '<'.code.toByte())) ||
                bytes.sliceArray(0..3).contentEquals(byteArrayOf('<'.code.toByte(), 0, 0, 0))
            ))) { "Office XML must use UTF-8 or UTF-16 encoding." }
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Office XML contains invalid text encoding.", error)
        }
        require(!text.contains('\u0000')) { "Office XML contains invalid text encoding." }
        val declaredEncoding = Regex("^\\s*<\\?xml\\s+[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(text.take(512))?.groupValues?.get(1)?.uppercase()
        require(declaredEncoding == null || declaredEncoding in setOf("UTF-8", "UTF8", "UTF-16", "UTF-16LE", "UTF-16BE")) {
            "Office XML must use UTF-8 or UTF-16 encoding."
        }
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) {
            "Office XML containing document type or entity declarations is unsupported."
        }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            .newPullParser().apply {
                try {
                    setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                } catch (_: XmlPullParserException) {
                    // kXML implementations may not expose this optional feature, even when
                    // disabling it. DTD and entity declarations were rejected above, before
                    // any parser sees these bytes; unsupported features cannot enable them.
                }
                setInput(StringReader(text))
            }
        return BoundedXmlParser(parser, limits)
    }

    private fun attribute(parser: XmlPullParser, localName: String): String? =
        (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == localName }
            ?.let { parser.getAttributeValue(it) }

    companion object {
        private val CELL_REFERENCE = Regex("([A-Za-z]{1,3})([1-9][0-9]{0,6})")
    }
}

/** Restrict work performed by the XML pull parser as well as decompressed byte size. */
private class BoundedXmlParser(
    private val parser: XmlPullParser,
    private val limits: ExtractionLimits,
) : XmlPullParser by parser {
    private var nodes = 0

    override fun next(): Int = parser.next().also { validateEvent(it) }
    override fun nextToken(): Int = parser.nextToken().also { validateEvent(it) }
    override fun nextTag(): Int = parser.nextTag().also { validateEvent(it) }

    override fun nextText(): String = parser.nextText().also {
        nodes++
        require(nodes <= limits.maxXmlNodes) { "Office XML exceeds the node limit." }
        require(it.length <= limits.maxXmlTextChars) { "An Office XML text value exceeds the extraction limit." }
    }

    private fun validateEvent(event: Int) {
        require(event != XmlPullParser.DOCDECL) { "Office XML document type declarations are unsupported." }
        if (event == XmlPullParser.START_TAG) {
            nodes++
            require(nodes <= limits.maxXmlNodes) { "Office XML exceeds the node limit." }
            require(parser.depth <= limits.maxXmlDepth) { "Office XML exceeds the nesting limit." }
            require(parser.name.length <= 256) { "An Office XML element name exceeds the extraction limit." }
            require(parser.attributeCount <= 64) { "An Office XML element has too many attributes." }
            for (index in 0 until parser.attributeCount) {
                require(parser.getAttributeValue(index).length <= limits.maxXmlAttributeChars) {
                    "An Office XML attribute exceeds the extraction limit."
                }
            }
            val parentNamespaces = parser.getNamespaceCount(parser.depth - 1)
            val namespaces = parser.getNamespaceCount(parser.depth)
            require(namespaces - parentNamespaces <= 64) { "An Office XML element has too many namespace declarations." }
            for (index in parentNamespaces until namespaces) {
                require(parser.getNamespaceUri(index).length <= limits.maxXmlAttributeChars) {
                    "An Office XML namespace exceeds the extraction limit."
                }
            }
        } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
            require(parser.text.length <= limits.maxXmlTextChars) { "An Office XML text value exceeds the extraction limit." }
        }
    }
}

internal class SectionCollector(private val maxChars: Int) {
    val sections = mutableListOf<DocumentSection>()
    val warnings = mutableListOf<String>()
    private var used = 0
    val full: Boolean get() = used >= maxChars

    fun add(locator: String, text: String, kind: String, numericValue: String? = null, isFormula: Boolean = false) {
        require(locator.length <= 256) { "A document source reference exceeds the extraction limit." }
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
