package com.offline.pocketagent.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
    }

    @Test fun `reads absolute package worksheet targets`() {
        val file = workbook("""<row r="1"><c r="A1"><v>42</v></c></row>""", "/xl/worksheets/sheet1.xml")
        assertEquals("42", OfficeParser().parseXlsx(file, "Absolute.xlsx").sections.single().text)
    }

    private fun workbook(rows: String, target: String = "worksheets/sheet1.xml", styles: String? = null): File = archive("book.xlsx", buildMap {
        putAll(mapOf(
        "xl/workbook.xml" to workbookXml(),
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="$target" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/></Relationships>""",
        "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><r><t>Rev</t></r><r><t>enue</t></r><rPh><t>ignored phonetics</t></rPh></si><si><t>Rent</t></si><si><t>123</t></si></sst>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""",
        ))
        if (styles != null) put("xl/styles.xml", styles)
    })

    private fun workbookXml() = """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Finance &amp; Ops" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private fun wordText(text: String) = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""

    private fun archive(name: String, parts: Map<String, String>): File {
        val directory = temporaryFolder.newFolder()
        val file = File(directory, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            parts.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
