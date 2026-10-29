package com.dharmawantoxi.docscan

import com.dharmawantoxi.docscan.office.OfficeExport
import com.dharmawantoxi.docscan.office.OfficeFormat
import com.dharmawantoxi.docscan.office.OfficeItem
import com.dharmawantoxi.docscan.office.OfficeItemType
import com.dharmawantoxi.docscan.office.OfficePackage
import com.dharmawantoxi.docscan.office.OfficeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OfficePackageTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "office-tests-" + System.nanoTime()).apply { mkdirs() }

    @Test fun wordReplacesOnlySelectedTextAndKeepsOtherParts() {
        val source = zip("sample.docx", docx())
        val output = zip("edited.docx", emptyList())
        val info = OfficePackage.inspect(source)
        assertEquals(OfficeFormat.WORD, info.format)
        assertEquals(listOf("word/document.xml", "word/header1.xml"), info.sections.map { it.part })
        val body = info.sections.first()
        val items = OfficePackage.readSection(source, body)
        assertEquals(listOf("Judul Lama", "Bagian lain", "Sel tabel"), items.map { it.text })
        OfficePackage.replace(source, output, body, items.first(), "Judul Baru")
        assertEquals(listOf("Judul Baru", "Bagian lain", "Sel tabel"), OfficePackage.readSection(output, body).map { it.text })
        assertEquals(listOf("Kepala"), OfficePackage.readSection(output, info.sections[1]).map { it.text })
        assertNotEquals(source.readBytes().contentHashCode(), output.readBytes().contentHashCode())
    }

    @Test fun wordRejectsStaleOrUnsafeEdits() {
        val source = zip("stale.docx", docx())
        val body = OfficePackage.inspect(source).sections.first()
        val item = OfficePackage.readSection(source, body).first()
        expectFailure(IOException::class) { OfficePackage.replace(source, zip("similar.docx", emptyList()), body, item.copy(text = "Lain"), "Baru") }
        expectFailure(IOException::class) { OfficePackage.replace(source, zip("same.docx", emptyList()), body, item, "Judul Lama") }
        expectFailure(IllegalArgumentException::class) { OfficePackage.replace(source, zip("newline.docx", emptyList()), body, item, "Dua\nbaris") }
        expectFailure(IOException::class) { OfficePackage.replace(source, zip("nul.docx", emptyList()), body, item, "Nul\u0000") }
        expectFailure(IllegalArgumentException::class) { OfficePackage.replace(source, zip("long.docx", emptyList()), body, item, "A".repeat(32768)) }
        try {
            OfficePackage.replace(source, source, body, item, "Baru")
            fail("Menulis ke file sumber harus ditolak")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun excelEditsCellsAndPreservesFormula() {
        val source = zip("sample.xlsx", xlsx())
        val changedXlsx = zip("changed.xlsx", emptyList())
        val numberXlsx = zip("number.xlsx", emptyList())
        val info = OfficePackage.inspect(source)
        assertEquals(OfficeFormat.EXCEL, info.format)
        assertEquals(listOf("Penjualan", "Kedua"), info.sections.map { it.label })
        val sheet = info.sections.first()
        val items = OfficePackage.readSection(source, sheet)
        assertEquals(listOf("A1", "B1", "D1", "E1"), items.map { it.key })
        assertEquals(OfficeItemType.NUMBER, items[1].type)
        assertEquals(OfficeItemType.BOOLEAN, items[2].type)
        // "Nama Lama" muncul dua kali sebagai shared string, tetapi hanya sel yang dipilih berubah.
        OfficePackage.replace(source, changedXlsx, sheet, items[0], "Nama Baru")
        OfficePackage.replace(changedXlsx, numberXlsx, sheet,
            OfficePackage.readSection(changedXlsx, sheet).first { it.key == "B1" }, "27.5")
        val changed = OfficePackage.readSection(numberXlsx, sheet).associateBy { it.key }
        assertEquals("Nama Baru", changed.getValue("A1").text)
        assertEquals("Nama Lama", changed.getValue("E1").text)
        assertEquals("27.5", changed.getValue("B1").text)
        assertEquals(OfficeItemType.NUMBER, changed.getValue("B1").type)
        assertEquals("TRUE", changed.getValue("D1").text)
        assertEquals("Isi dua", OfficePackage.readSection(numberXlsx, info.sections[1]).single().text)
        val worksheet = entry(numberXlsx, "xl/worksheets/sheet1.xml")
        assertTrue(worksheet.contains("<f>B1*2</f>"))
        assertTrue(entry(numberXlsx, "xl/workbook.xml").contains("fullCalcOnLoad=\"1\""))
    }

    @Test fun excelRejectsInvalidValuesAndProtectedSheets() {
        val source = zip("invalid.xlsx", xlsx())
        val sheet = OfficePackage.inspect(source).sections.first()
        val items = OfficePackage.readSection(source, sheet)
        expectFailure(IOException::class) { OfficePackage.replace(source, zip("bad-number.xlsx", emptyList()), sheet, items[1], "dua belas") }
        expectFailure(IOException::class) { OfficePackage.replace(source, zip("bad-bool.xlsx", emptyList()), sheet, items[2], "mungkin") }
        // Proteksi lembar terdeteksi saat bagian dibuka, bukan saat paket diperiksa.
        val protectedSheet = zip("protected.xlsx", xlsx(sheetProtection = true))
        val protectedSection = OfficePackage.inspect(protectedSheet).sections.first()
        expectFailure(IOException::class) { OfficePackage.readSection(protectedSheet, protectedSection) }
    }

    @Test fun powerpointEditsSlideTextAndKeepsOtherSlides() {
        val source = zip("sample.pptx", pptx())
        val output = zip("edited.pptx", emptyList())
        val info = OfficePackage.inspect(source)
        assertEquals(OfficeFormat.POWERPOINT, info.format)
        assertEquals(listOf("Slide 1", "Slide 2"), info.sections.map { it.label })
        val first = info.sections.first()
        val item = OfficePackage.readSection(source, first).first()
        OfficePackage.replace(source, output, first, item, "Slide Baru")
        assertEquals(listOf("Slide Baru", "Isi"), OfficePackage.readSection(output, first).map { it.text })
        assertEquals(listOf("Slide Kedua"), OfficePackage.readSection(output, info.sections[1]).map { it.text })
    }

    @Test fun rejectsUnsupportedOrUnsafePackages() {
        expectFailure(IOException::class) { OfficePackage.inspect(zip("macro.docx", docx(macro = true))) }
        expectFailure(IOException::class) { OfficePackage.inspect(zip("doctype.docx", docx(doctype = true))) }
        expectFailure(IOException::class) { OfficePackage.inspect(zip("signed.docx", docx(signed = true))) }
        expectFailure(IOException::class) { OfficePackage.inspect(zip("protected.docx", docx(wordProtection = true))) }
        expectFailure(IOException::class) { OfficePackage.inspect(zip("external.xlsx", xlsx(externalSheet = true))) }
        expectFailure(IOException::class) { OfficePackage.inspect(zip("trav.docx", docx(headerTarget = "../../evil.xml"))) }
        expectFailure(IOException::class) { OfficePackage.inspect(zip("duplicate.docx", docx() + ("word/header1.xml" to "<x/>".toByteArray()))) }
        expectFailure(IOException::class) { OfficePackage.inspect(bytes("plain.docx", "bukan zip".toByteArray())) }
        expectFailure(IOException::class) { OfficePackage.inspect(bytes("empty.docx", ByteArray(0))) }
        val source = zip("type.docx", docx())
        val section = OfficePackage.inspect(source).sections.first()
        val item = OfficePackage.readSection(source, section).first()
        // Bagian yang tidak terdaftar tidak boleh ditulis.
        expectFailure(IOException::class) {
            OfficePackage.replace(source, zip("type-out.docx", emptyList()),
                OfficeSection("word/styles.xml", "Gaya"), item, "Baru")
        }
    }

    @Test fun exportProducesEditableOfficeFiles() {
        val pages = OfficeExport.splitPages("--- Halaman 1 ---\nNama & Judul\n=2+2\n\n--- Halaman 2 ---\nSlide <Kedua>")
        assertEquals(listOf("Nama & Judul\n=2+2", "Slide <Kedua>"), pages)
        for (format in OfficeFormat.entries) {
            val target = output("export.${format.extension}")
            target.outputStream().use { OfficeExport.write(format, pages, it) }
            val info = OfficePackage.inspect(target)
            assertEquals(format, info.format)
            when (format) {
                OfficeFormat.WORD -> assertEquals(listOf("Nama & Judul", "=2+2", "Slide <Kedua>"),
                    info.sections.flatMap { OfficePackage.readSection(target, it) }.map { it.text })
                OfficeFormat.EXCEL -> {
                    assertEquals(listOf("Halaman 1", "Halaman 2"), info.sections.map { it.label })
                    assertEquals(listOf("Nama & Judul", "=2+2"), OfficePackage.readSection(target, info.sections[0]).map { it.text })
                }
                OfficeFormat.POWERPOINT -> assertEquals(2, info.sections.size)
            }
            // Hasil ekspor dapat langsung diedit seperti file Office biasa.
            val section = info.sections.first()
            val item = OfficePackage.readSection(target, section).first()
            val edited = output("export-edited.${format.extension}")
            OfficePackage.replace(target, edited, section, item, "Diganti")
            assertEquals("Diganti", OfficePackage.readSection(edited, section).first().text)
        }
    }

    @Test fun exportHandlesPaginationGracefully() {
        assertEquals(listOf("Tanpa penanda"), OfficeExport.splitPages("Tanpa penanda"))
        assertEquals(listOf("B"), OfficeExport.splitPages("--- Halaman 2 ---\nB"))
        assertEquals(listOf("A", "C"), OfficeExport.splitPages("--- Halaman 1 ---\nA\n\n--- Halaman 3 ---\nC"))
        assertEquals(listOf("--- Halaman 2 ---\nA\n\n--- Halaman 1 ---\nB"), OfficeExport.splitPages("--- Halaman 2 ---\nA\n\n--- Halaman 1 ---\nB"))
        assertEquals(listOf("", "Kedua"), OfficeExport.splitPages("--- Halaman 1 ---\n[Tidak ada teks yang dikenali]\n\n--- Halaman 2 ---\nKedua"))
        assertEquals(listOf("Satu"), OfficeExport.splitPages("--- Halaman 1 ---\nSatu\n"))
        expectFailure(IllegalArgumentException::class) { OfficeExport.write(OfficeFormat.WORD, emptyList(), ByteArrayOutputStream()) }
        expectFailure(IOException::class) { OfficeExport.write(OfficeFormat.WORD, listOf("Nul\u0000"), ByteArrayOutputStream()) }
    }

    private fun expectFailure(type: kotlin.reflect.KClass<out Throwable>, block: () -> Unit) {
        try {
            block()
            fail("Seharusnya gagal dengan ${type.simpleName}")
        } catch (failure: Throwable) {
            if (failure is AssertionError) throw failure
            assertTrue("Kesalahan tak terduga: $failure", type.java.isInstance(failure))
        }
    }

    private fun output(name: String): File = File(directory, name)

    private fun bytes(name: String, data: ByteArray): File = File(directory, name).apply { writeBytes(data) }

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val target = File(directory, name)
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (entryName, data) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return target
    }

    private fun entry(file: File, name: String): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry(name) ?: return ""
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun contentTypes(vararg overrides: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        overrides.forEach { (part, type) -> append("<Override PartName=\"$part\" ContentType=\"$type\"/>") }
        append("</Types>")
    }

    private fun docx(macro: Boolean = false, doctype: Boolean = false, signed: Boolean = false,
                     wordProtection: Boolean = false,
                     headerTarget: String = "header1.xml"): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries.add("[Content_Types].xml" to contentTypes(
            "/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "/word/header1.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"
        ).toByteArray())
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            "<w:p><w:r><w:t>Judul Lama</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Bagian lain</w:t></w:r></w:p>" +
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Sel tabel</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:sectPr/></w:body></w:document>"
        entries.add("word/document.xml" to xml.toByteArray())
        entries.add("word/_rels/document.xml.rels" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"$headerTarget\"/></Relationships>").toByteArray())
        entries.add("word/header1.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:p><w:r><w:t>Kepala</w:t></w:r></w:p></w:hdr>").toByteArray())
        if (wordProtection) entries.add("word/settings.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:documentProtection w:edit=\"readOnly\" w:enforcement=\"1\"/></w:settings>").toByteArray())
        if (macro) entries[0] = "[Content_Types].xml" to contentTypes(
            "/word/document.xml" to "application/vnd.ms-word.document.macroEnabled.main+xml",
            "/word/header1.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"
        ).toByteArray()
        if (doctype) entries.add("word/settings.xml" to "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY x \"y\">]><w:settings/>".toByteArray())
        if (signed) entries.add("_xmlsignatures/sig1.xml" to "<sig/>".toByteArray())
        return entries
    }

    private fun xlsx(sheetProtection: Boolean = false, externalSheet: Boolean = false): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries.add("[Content_Types].xml" to contentTypes(
            "/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "/xl/worksheets/sheet1.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
            "/xl/worksheets/sheet2.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
            "/xl/sharedStrings.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"
        ).toByteArray())
        entries.add("xl/workbook.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            "<sheet name=\"Penjualan\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Kedua\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>").toByteArray())
        entries.add("xl/_rels/workbook.xml.rels" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"${if (externalSheet) "http://example.com/sheet2" else "worksheets/sheet2.xml"}\"${if (externalSheet) " TargetMode=\"External\"" else ""}/></Relationships>").toByteArray())
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>12.5</v></c><c r=\"C1\"><f>B1*2</f><v>25</v></c><c r=\"D1\" t=\"b\"><v>1</v></c><c r=\"E1\" t=\"s\"><v>2</v></c></row>" +
            "</sheetData>${if (sheetProtection) "<sheetProtection sheet=\"1\"/>" else ""}</worksheet>"
        entries.add("xl/worksheets/sheet1.xml" to sheet.toByteArray())
        entries.add("xl/worksheets/sheet2.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Isi dua</t></is></c></row></sheetData></worksheet>".toByteArray())
        entries.add("xl/sharedStrings.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>Nama Lama</t></si><si><t>Lain</t></si><si><t>Nama Lama</t></si></sst>".toByteArray())
        return entries
    }

    private fun pptx(): List<Pair<String, ByteArray>> = listOf(
        "[Content_Types].xml" to contentTypes(
            "/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "/ppt/slides/slide1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slide+xml",
            "/ppt/slides/slide2.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        ).toByteArray(),
        "ppt/presentation.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId3\"/></p:sldIdLst></p:presentation>").toByteArray(),
        "ppt/_rels/presentation.xml.rels" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide2.xml\"/></Relationships>").toByteArray(),
        "ppt/slides/slide1.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:sp><p:txBody>" +
            "<a:p><a:r><a:t>Slide Lama</a:t></a:r></a:p><a:p><a:r><a:t>Isi</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>").toByteArray(),
        "ppt/slides/slide2.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:sp><p:txBody>" +
            "<a:p><a:r><a:t>Slide Kedua</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>").toByteArray()
    )
}
