package com.offline.pocketagent.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeParserTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `reads ordered Word paragraphs tables and header text with useful locators`() {
        val file = archive("letter.docx", mapOf(
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> world</w:t></w:r></w:p>
                    <w:tbl><w:tr>
                      <w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>Amount</w:t></w:r></w:p></w:tc>
                    </w:tr><w:tr>
                      <w:tc><w:p><w:r><w:t>Rent</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>1200</w:t></w:r></w:p></w:tc>
                    </w:tr></w:tbl>
                    <w:p><w:r><w:t>Last</w:t><w:tab/><w:t>line</w:t><w:br/><w:t>next</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
            "word/header1.xml" to """<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>Private</w:t></w:r></w:p></w:hdr>""",
        ))
        val result = OfficeParser().parseDocx(file, "Letter.docx")
        assertEquals("Letter.docx", result.name)
        assertEquals("docx", result.kind)
        assertEquals(listOf("Hello world", "Item | Amount", "Rent | 1200", "Last\tline\nnext", "Private"), result.sections.map { it.text })
        assertEquals("body paragraph 1", result.sections.first().locator)
        assertEquals("body table 1 row 2", result.sections[2].locator)
        assertEquals("header1 paragraph 1", result.sections.last().locator)
        assertTrue(result.warnings.any { it.contains("page layout") })
        assertTrue(result.extractionComplete)
        assertNull(result.totalPages)
        assertNull(result.processedPages)
    }

    @Test fun `reads spreadsheet values and rich shared strings with source cell references`() {
        val file = workbook("""
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Amount</t></is></c></row>
            <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>125.50</v></c><c r="C2" t="b"><v>1</v></c></row>
        """.trimIndent())
        val result = OfficeParser().parseXlsx(file, "Budget.xlsx")
        assertEquals(listOf("Revenue", "Amount", "Rent", "125.50", "TRUE"), result.sections.map { it.text })
        assertEquals("'Finance & Ops'!B2", result.sections[3].locator)
        assertTrue(result.sections.all { it.kind == "cell" })
        assertFalse(result.warnings.any { it.startsWith("Formulas") })
    }

    @Test fun `reports cached formula results without evaluating missing results`() {
        val file = workbook("""
            <row r="1"><c r="A1"><v>10</v></c><c r="B1"><f>A1*2</f><v>20</v></c><c r="C1"><f>SUM(A1:B1)</f></c></row>
        """.trimIndent())
        val result = OfficeParser().parseXlsx(file, "Budget.xlsx")
        assertEquals("Formula: =A1*2; cached value: 20", result.sections[1].text)
        assertEquals("Formula: =SUM(A1:B1); cached value: [not available]", result.sections[2].text)
        assertEquals("20", result.sections[1].numericValue)
        assertTrue(result.sections[1].isFormula)
        assertNull(result.sections[2].numericValue)
        assertTrue(result.sections[2].isFormula)
        assertTrue(result.warnings.any { it.contains("not calculated") && it.contains("out of date") })
    }

    @Test fun `stops at global cell limit and tells the reader`() {
        val file = workbook("""<row r="1"><c r="A1"><v>1</v></c><c r="B1"><v>2</v></c><c r="C1"><v>3</v></c></row>""")
        val result = OfficeParser(ExtractionLimits(maxCells = 2)).parseXlsx(file, "Budget.xlsx")
        assertEquals(2, result.sections.size)
        assertTrue(result.warnings.any { it.contains("2-cell limit") })
        assertFalse(result.extractionComplete)
    }

    @Test fun `numeric metadata excludes text bool dates and invalid exponent values`() {
        val file = workbook("""
            <row r="1">
              <c r="A1" t="s"><v>2</v></c>
              <c r="B1" t="inlineStr"><is><t>456</t></is></c>
              <c r="C1" t="b"><v>1</v></c>
              <c r="D1" t="d"><v>2026-10-24</v></c>
              <c r="E1" t="n"><v>12.50</v></c>
              <c r="F1"><v>1.5E+2</v></c>
              <c r="G1"><v>1E99999999</v></c>
            </row>
        """.trimIndent())
        val result = OfficeParser().parseXlsx(file, "Numbers.xlsx")
        assertEquals("123", result.sections[0].text)
        assertEquals(listOf(null, null, null, null, "12.50", "1.5E+2", null), result.sections.map { it.numericValue })
        assertTrue(result.sections.none { it.isFormula })
    }

    @Test fun `date-formatted serial numbers are not offered to numeric aggregate tools`() {
        val styles = """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <numFmts><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/></numFmts>
              <cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="165"/></cellXfs>
            </styleSheet>
        """.trimIndent()
        val file = workbook("""<row r="1"><c r="A1" s="1"><v>46000</v></c><c r="B1" s="2"><v>46001</v></c><c r="C1"><v>20</v></c></row>""", styles = styles)
        val result = OfficeParser().parseXlsx(file, "Dates.xlsx")
        assertEquals(listOf(null, null, "20"), result.sections.map { it.numericValue })
    }

    @Test fun `truncates text with a visible warning`() {
        val file = archive("short.docx", mapOf("word/document.xml" to wordText("Long paragraph")))
        val result = OfficeParser(ExtractionLimits(maxTextChars = 5)).parseDocx(file, "Short.docx")
        assertEquals("Long ", result.sections.single().text)
        assertTrue(result.warnings.any { it.contains("5-character limit") })
        assertFalse(result.extractionComplete)
    }

    @Test fun `rejects oversized decompressed components`() {
        val file = archive("oversize.docx", mapOf("word/document.xml" to wordText("hello")))
        val error = runCatching { OfficeParser(ExtractionLimits(maxZipEntryBytes = 32)).parseDocx(file, "oversize.docx") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("size limit"))
    }

    @Test fun `rejects XML entity declarations instead of expanding them`() {
        val text = """<?xml version="1.0"?><!DOCTYPE document [<!ENTITY secret "contents">]><document>&secret;</document>"""
        val file = archive("entities.docx", mapOf("word/document.xml" to text))
        val error = runCatching { OfficeParser().parseDocx(file, "entities.docx") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("entity declarations"))
    }

    @Test fun `never follows external workbook relationships`() {
        val file = archive("external.xlsx", mapOf(
            "xl/workbook.xml" to workbookXml(),
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="https://example.com/workbook.xml" TargetMode="External"/></Relationships>""",
        ))
        val result = OfficeParser().parseXlsx(file, "External.xlsx")
        assertTrue(result.sections.isEmpty())
        assertTrue(result.warnings.any { it.contains("could not be read") })
        assertFalse(result.extractionComplete)
    }

    @Test fun `reads absolute package worksheet targets`() {
        val file = workbook("""<row r="1"><c r="A1"><v>42</v></c></row>""", "/xl/worksheets/sheet1.xml")
        assertEquals("42", OfficeParser().parseXlsx(file, "Absolute.xlsx").sections.single().text)
    }

    @Test fun `UTF16 document text remains readable after canonical decoding`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + wordText("Café résumé")
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml.toByteArray(Charsets.UTF_16LE)
        val file = archiveBytes("unicode.docx", mapOf("word/document.xml" to bytes))
        assertEquals("Café résumé", OfficeParser().parseDocx(file, "unicode.docx").sections.single().text)
    }

    @Test fun `UTF16 entity declarations cannot bypass the declaration check`() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE document [<!ENTITY secret SYSTEM "file:///data/local/private">]><document>&secret;</document>"""
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val prefix = if (charset == Charsets.UTF_16LE) byteArrayOf(0xff.toByte(), 0xfe.toByte()) else byteArrayOf(0xfe.toByte(), 0xff.toByte())
            val file = archiveBytes("entities.docx", mapOf("word/document.xml" to (prefix + xml.toByteArray(charset))))
            assertRejected("entity declarations") { OfficeParser().parseDocx(file, "entities.docx") }
        }
    }

    @Test fun `UTF32 encoded declarations are rejected before parser encoding sniffing`() {
        val xml = """<?xml version="1.0" encoding="UTF-32"?><!DOCTYPE document [<!ENTITY secret "expanded">]><document>&secret;</document>"""
        for (encoding in listOf("UTF-32LE", "UTF-32BE")) {
            val file = archiveBytes("utf32.docx", mapOf("word/document.xml" to xml.toByteArray(Charset.forName(encoding))))
            assertRejected("UTF-8 or UTF-16") { OfficeParser().parseDocx(file, "utf32.docx") }
        }
    }

    @Test fun `unsupported declared encoding is rejected consistently`() {
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + wordText("Plain ASCII")
        val file = archive("encoding.docx", mapOf("word/document.xml" to xml))
        assertRejected("UTF-8 or UTF-16") { OfficeParser().parseDocx(file, "encoding.docx") }
    }

    @Test fun `malformed UTF8 cannot be silently replaced before security checks`() {
        val bytes = wordText("text").toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte(), 0x28)
        val file = archiveBytes("invalid.docx", mapOf("word/document.xml" to bytes))
        assertRejected("invalid text encoding") { OfficeParser().parseDocx(file, "invalid.docx") }
    }

    @Test fun `repeated worksheet references share an actual decompression budget`() {
        val workbook = """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="One" r:id="r1"/><sheet name="Two" r:id="r2"/><sheet name="Three" r:id="r3"/></sheets></workbook>"""
        val relationships = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Target="worksheets/sheet.xml"/><Relationship Id="r2" Target="worksheets/sheet.xml"/><Relationship Id="r3" Target="worksheets/sheet.xml"/></Relationships>"""
        val worksheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>42</v></c></row></sheetData></worksheet>"""
        val parts = mapOf("xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to relationships, "xl/worksheets/sheet.xml" to worksheet)
        val declaredTotal = parts.values.sumOf { it.toByteArray(Charsets.UTF_8).size }.toLong()
        val file = archive("repeated.xlsx", parts)
        val parser = OfficeParser(ExtractionLimits(maxZipTotalBytes = declaredTotal + worksheet.length / 2))
        assertRejected("total decompressed read limit") { parser.parseXlsx(file, "repeated.xlsx") }
    }

    @Test fun `forged small entry count is checked against real directory headers`() {
        val file = archive("count.docx", mapOf("word/document.xml" to wordText("one"), "word/header1.xml" to wordText("two")))
        val bytes = file.readBytes()
        val end = bytes.size - 22
        bytes[end + 8] = 1
        bytes[end + 9] = 0
        bytes[end + 10] = 1
        bytes[end + 11] = 0
        file.writeBytes(bytes)
        assertRejected("directory is inconsistent") { OfficeParser().parseDocx(file, "count.docx") }
    }

    @Test fun `deep XML nesting is stopped before exhausting parser resources`() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
            "<w:span>".repeat(30) + "text" + "</w:span>".repeat(30) + "</w:body></w:document>"
        val file = archive("deep.docx", mapOf("word/document.xml" to xml))
        assertRejected("nesting limit") { OfficeParser(ExtractionLimits(maxXmlDepth = 16)).parseDocx(file, "deep.docx") }
    }

    @Test fun `excess XML nodes and oversized attributes are rejected`() {
        val nodes = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
            "<w:span/>".repeat(50) + "</w:body></w:document>"
        val nodeFile = archive("nodes.docx", mapOf("word/document.xml" to nodes))
        assertRejected("node limit") { OfficeParser(ExtractionLimits(maxXmlNodes = 20)).parseDocx(nodeFile, "nodes.docx") }
        val attributeXml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p value="${"[".repeat(200)}"/></w:body></w:document>"""
        val attributeFile = archive("attribute.docx", mapOf("word/document.xml" to attributeXml))
        assertRejected("attribute exceeds") { OfficeParser(ExtractionLimits(maxXmlAttributeChars = 100)).parseDocx(attributeFile, "attribute.docx") }
    }

    @Test fun `malformed date formats cannot cause repeated bracket rescanning`() {
        val styles = """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts><numFmt numFmtId="165" formatCode="${"[".repeat(7_000)}0"/></numFmts><cellXfs><xf numFmtId="165"/></cellXfs></styleSheet>"""
        val file = workbook("""<row r="1"><c r="A1"><v>25</v></c></row>""", styles = styles)
        assertEquals("25", OfficeParser().parseXlsx(file, "format.xlsx").sections.single().numericValue)
    }

    @Test fun `namespace declarations cannot bypass XML attribute budgets`() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:extra="${"x".repeat(200)}"><w:body/></w:document>"""
        val file = archive("namespace.docx", mapOf("word/document.xml" to xml))
        assertRejected("namespace exceeds") { OfficeParser(ExtractionLimits(maxXmlAttributeChars = 100)).parseDocx(file, "namespace.docx") }
    }

    @Test fun `oversized worksheet names and cell addresses cannot amplify source metadata`() {
        val nameFile = workbook("""<row r="1"><c r="A1"><v>1</v></c></row>""", sheetName = "x".repeat(8_000))
        assertRejected("invalid name") { OfficeParser().parseXlsx(nameFile, "name.xlsx") }
        val referenceFile = workbook("""<row r="1"><c r="${"A".repeat(8_000)}"><v>1</v></c></row>""")
        assertRejected("A1 cell address") { OfficeParser().parseXlsx(referenceFile, "reference.xlsx") }
    }

    @Test fun `cell references must be within Excel bounds and unique`() {
        for (reference in listOf("XFE1", "XFD1048577", "A0", "AAAA1")) {
            val file = workbook("""<row r="1"><c r="$reference"><v>1</v></c></row>""")
            assertRejected("cell address") { OfficeParser().parseXlsx(file, "reference.xlsx") }
        }
        val duplicate = workbook("""<row r="1"><c r="A1"><v>1</v></c><c r="a1"><v>2</v></c></row>""")
        assertRejected("duplicate cell addresses") { OfficeParser().parseXlsx(duplicate, "duplicate.xlsx") }
        val edge = workbook("""<row r="1048576"><c r="xfd1048576"><v>7</v></c></row>""")
        assertEquals("'Finance & Ops'!XFD1048576", OfficeParser().parseXlsx(edge, "edge.xlsx").sections.single().locator)
    }

    @Test fun `Word footnotes endnotes and comments join the complete text extraction without invented pages`() {
        val parts = mutableMapOf("word/document.xml" to wordText("Main body"))
        for (part in listOf("footnotes", "endnotes", "comments")) {
            parts["word/$part.xml"] = wordText("Text from $part")
        }
        val result = OfficeParser().parseDocx(archive("notes.docx", parts), "notes.docx")
        assertEquals(listOf("Main body", "Text from footnotes", "Text from endnotes", "Text from comments"), result.sections.map { it.text })
        assertEquals("footnotes paragraph 1", result.sections[1].locator)
        assertTrue(result.extractionComplete)
        assertNull(result.totalPages)
        assertNull(result.processedPages)
    }

    @Test fun `Word header part cutoff is recorded as incomplete coverage`() {
        val parts = mutableMapOf("word/document.xml" to wordText("Main"))
        for (index in 1..41) parts["word/header$index.xml"] = wordText("Header $index")
        val result = OfficeParser().parseDocx(archive("headers.docx", parts), "headers.docx")
        assertEquals(41, result.sections.size)
        assertFalse(result.extractionComplete)
        assertTrue(result.warnings.any { it.contains("first 40 Word header") })
    }

    @Test fun `worksheet cutoff and unavailable shared strings prevent a complete extraction claim`() {
        val workbookMetadata = workbookXml().replace("</sheets>", """<sheet name="Extra" sheetId="2" r:id="rId1"/></sheets>""")
        val file = archive("sheets.xlsx", mapOf(
            "xl/workbook.xml" to workbookMetadata,
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""",
        ))
        val result = OfficeParser(ExtractionLimits(maxSheets = 1)).parseXlsx(file, "sheets.xlsx")
        assertFalse(result.extractionComplete)
        assertTrue(result.warnings.any { it.contains("first 1 worksheets") })
        val missingString = workbook("""<row r="1"><c r="A1" t="s"><v>9999</v></c></row>""")
        assertFalse(OfficeParser().parseXlsx(missingString, "string.xlsx").extractionComplete)
    }

    @Test fun `known unsupported Word text boxes prevent a full coverage claim`() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Main</w:t></w:r><w:txbxContent><w:p><w:r><w:t>Boxed</w:t></w:r></w:p></w:txbxContent></w:p></w:body></w:document>"""
        val result = OfficeParser().parseDocx(archive("boxes.docx", mapOf("word/document.xml" to xml)), "boxes.docx")
        assertFalse(result.extractionComplete)
        assertTrue(result.warnings.any { it.contains("text boxes") })
    }

    private fun assertRejected(message: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException containing '$message', got $error", error is IllegalArgumentException)
        assertTrue("Expected '$message', got '${error?.message}'", error!!.message!!.contains(message))
    }

    private fun workbook(rows: String, target: String = "worksheets/sheet1.xml", styles: String? = null, sheetName: String = "Finance & Ops"): File = archive("book.xlsx", buildMap {
        putAll(mapOf(
        "xl/workbook.xml" to workbookXml(sheetName),
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="$target" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/></Relationships>""",
        "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><r><t>Rev</t></r><r><t>enue</t></r><rPh><t>ignored phonetics</t></rPh></si><si><t>Rent</t></si><si><t>123</t></si></sst>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""",
        ))
        if (styles != null) put("xl/styles.xml", styles)
    })

    private fun workbookXml(name: String = "Finance & Ops"): String {
        val escaped = name.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$escaped" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    }

    private fun wordText(text: String) = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""

    private fun archive(name: String, parts: Map<String, String>): File = archiveBytes(name, parts.mapValues { it.value.toByteArray(Charsets.UTF_8) })

    private fun archiveBytes(name: String, parts: Map<String, ByteArray>): File {
        val directory = temporaryFolder.newFolder()
        val file = File(directory, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            parts.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
