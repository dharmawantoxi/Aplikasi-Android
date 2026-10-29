package com.dharmawantoxi.docscan.office

import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ekspor teks OCR yang dapat diedit. Ini bukan rekonstruksi tata letak dokumen. */
object OfficeExport {
    private const val MAX_PAGES = 25
    private const val MAX_CHARS = 200_000
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 128
    private const val MAX_CELL = 32_767
    private const val REL_OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL_PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PRESENTATION = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val SPREADSHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** Penanda pada editor OCR dipakai sebagai batas halaman jika urutannya masih utuh. */
    fun splitPages(text: String): List<String> {
        if (text.length > MAX_CHARS) throw IOException("Teks terlalu panjang untuk ekspor Office")
        OfficePackage.checkXmlText(text)
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val pattern = Regex("(?m)^--- (?:Halaman|Page) ([0-9]+) ---[ \\t]*$")
        val matches = pattern.findAll(normalized).toList()
        // Penanda hanya dipercaya jika teks dimulai dengan penanda dan nomornya menaik;
        // jika tidak, seluruh teks dianggap satu halaman agar tidak ada isi yang hilang.
        val numbers = matches.map { it.groupValues[1].toIntOrNull() }
        if (matches.isEmpty() || matches.first().range.first != 0 || matches.size > MAX_PAGES ||
            numbers.any { it == null } || numbers.filterNotNull().zipWithNext().any { (first, second) -> second <= first }) {
            return listOf(normalized)
        }
        return matches.mapIndexed { index, match ->
            val start = (match.range.last + 1).let { if (normalized.getOrNull(it) == '\n') it + 1 else it }
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else normalized.length
            normalized.substring(start, end).trimEnd('\n').let { if (it == "[Tidak ada teks yang dikenali]" || it == "[No text recognized]") "" else it }
        }
    }

    fun write(format: OfficeFormat, pages: List<String>, output: OutputStream, english: Boolean = false) {
        require(pages.size in 1..MAX_PAGES) { "Dokumen harus berisi 1 sampai $MAX_PAGES halaman" }
        if (pages.sumOf { it.length.toLong() } > MAX_CHARS) throw IOException("Teks terlalu panjang untuk ekspor Office")
        pages.forEach(OfficePackage::checkXmlText)
        val normalized = pages.map { it.replace("\r\n", "\n").replace('\r', '\n') }
        ZipOutputStream(output.buffered()).use { zip ->
            when (format) {
                OfficeFormat.WORD -> word(zip, normalized)
                OfficeFormat.EXCEL -> excel(zip, normalized, english)
                OfficeFormat.POWERPOINT -> powerpoint(zip, normalized)
            }
        }
    }

    private fun word(zip: ZipOutputStream, pages: List<String>) {
        val doc = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"$WORD\"><w:body>")
            pages.forEachIndexed { index, page ->
                if (index > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                page.split('\n').forEach { line ->
                    append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    append(escape(line))
                    append("</w:t></w:r></w:p>")
                }
            }
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
            append("</w:body></w:document>")
        }
        put(zip, "[Content_Types].xml", contentTypes(listOf("/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")))
        put(zip, "_rels/.rels", rootRels("word/document.xml"))
        put(zip, "word/document.xml", doc)
    }

    private fun excel(zip: ZipOutputStream, pages: List<String>, english: Boolean) {
        val overrides = mutableListOf(
            "/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "/xl/styles.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"
        )
        pages.indices.forEach { overrides.add("/xl/worksheets/sheet${it + 1}.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml") }
        put(zip, "[Content_Types].xml", contentTypes(overrides))
        put(zip, "_rels/.rels", rootRels("xl/workbook.xml"))
        put(zip, "xl/workbook.xml", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"$SPREADSHEET\" xmlns:r=\"$REL_OFFICE\"><sheets>")
            pages.indices.forEach { append("<sheet name=\"${if (english) "Page" else "Halaman"} ${it + 1}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>") }
            append("</sheets><calcPr fullCalcOnLoad=\"1\"/></workbook>")
        })
        put(zip, "xl/_rels/workbook.xml.rels", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"$REL_PACKAGE\">")
            pages.indices.forEach { append("<Relationship Id=\"rId${it + 1}\" Type=\"$REL_OFFICE/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>") }
            append("<Relationship Id=\"rId${pages.size + 1}\" Type=\"$REL_OFFICE/styles\" Target=\"styles.xml\"/></Relationships>")
        })
        put(zip, "xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$SPREADSHEET"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        pages.forEachIndexed { index, page ->
            val lines = page.split('\n')
            if (lines.size > MAX_ROWS) throw IOException("Terlalu banyak baris untuk ekspor Excel")
            put(zip, "xl/worksheets/sheet${index + 1}.xml", buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"$SPREADSHEET\"><sheetData>")
                lines.forEachIndexed { row, line ->
                    val columns = line.split(Regex("\\t| {2,}"))
                    if (columns.size > MAX_COLUMNS) throw IOException("Terlalu banyak kolom untuk ekspor Excel")
                    append("<row r=\"${row + 1}\">")
                    columns.forEachIndexed { col, value ->
                        if (value.length > MAX_CELL) throw IOException("Isi sel Excel terlalu panjang")
                        // inlineStr memastikan teks yang diawali '=' tidak menjadi formula.
                        append("<c r=\"${columnName(col + 1)}${row + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        append(escape(value))
                        append("</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            })
        }
    }

    private fun powerpoint(zip: ZipOutputStream, pages: List<String>) {
        val overrides = mutableListOf(
            "/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "/ppt/slideMasters/slideMaster1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml",
            "/ppt/slideLayouts/slideLayout1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml",
            "/ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml"
        )
        pages.indices.forEach { overrides.add("/ppt/slides/slide${it + 1}.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slide+xml") }
        put(zip, "[Content_Types].xml", contentTypes(overrides))
        put(zip, "_rels/.rels", rootRels("ppt/presentation.xml"))
        put(zip, "ppt/presentation.xml", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:presentation xmlns:a=\"$DRAWING\" xmlns:r=\"$REL_OFFICE\" xmlns:p=\"$PRESENTATION\">")
            append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
            pages.indices.forEach { append("<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>") }
            append("</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:defaultTextStyle/></p:presentation>")
        })
        put(zip, "ppt/_rels/presentation.xml.rels", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"$REL_PACKAGE\">")
            append("<Relationship Id=\"rId1\" Type=\"$REL_OFFICE/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
            pages.indices.forEach { append("<Relationship Id=\"rId${it + 2}\" Type=\"$REL_OFFICE/slide\" Target=\"slides/slide${it + 1}.xml\"/>") }
            append("</Relationships>")
        })
        put(zip, "ppt/slideMasters/slideMaster1.xml", master())
        put(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$REL_PACKAGE"><Relationship Id="rId1" Type="$REL_OFFICE/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$REL_OFFICE/theme" Target="../theme/theme1.xml"/></Relationships>""")
        put(zip, "ppt/slideLayouts/slideLayout1.xml", layout())
        put(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$REL_PACKAGE"><Relationship Id="rId1" Type="$REL_OFFICE/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""")
        put(zip, "ppt/theme/theme1.xml", theme())
        pages.forEachIndexed { index, page ->
            put(zip, "ppt/slides/slide${index + 1}.xml", slide(page))
            put(zip, "ppt/slides/_rels/slide${index + 1}.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$REL_PACKAGE"><Relationship Id="rId1" Type="$REL_OFFICE/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>""")
        }
    }

    private fun slide(text: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:a=\"$DRAWING\" xmlns:r=\"$REL_OFFICE\" xmlns:p=\"$PRESENTATION\"><p:cSld><p:spTree>")
        append(groupShape())
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Teks OCR\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
        append("<p:spPr><a:xfrm><a:off x=\"457200\" y=\"365760\"/><a:ext cx=\"8229600\" cy=\"4419600\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>")
        append("<p:txBody><a:bodyPr wrap=\"square\"><a:normAutofit/></a:bodyPr><a:lstStyle/>")
        text.split('\n').forEach { line ->
            append("<a:p><a:r><a:rPr lang=\"id-ID\" sz=\"1800\"/><a:t xml:space=\"preserve\">")
            append(escape(line))
            append("</a:t></a:r><a:endParaRPr lang=\"id-ID\"/></a:p>")
        }
        append("</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
    }

    private fun groupShape(): String = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

    private fun master(): String = """<?xml version="1.0" encoding="UTF-8"?><p:sldMaster xmlns:a="$DRAWING" xmlns:r="$REL_OFFICE" xmlns:p="$PRESENTATION"><p:cSld><p:spTree>${groupShape()}</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"""

    private fun layout(): String = """<?xml version="1.0" encoding="UTF-8"?><p:sldLayout xmlns:a="$DRAWING" xmlns:r="$REL_OFFICE" xmlns:p="$PRESENTATION" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>${groupShape()}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private fun theme(): String {
        val colors = listOf("dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "1F497D", "lt2" to "EEECE1",
            "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5", "accent4" to "FFC000",
            "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72")
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a:theme xmlns:a=\"$DRAWING\" name=\"DocScan\"><a:themeElements><a:clrScheme name=\"DocScan\">")
            colors.forEach { (name, color) -> append("<a:$name><a:srgbClr val=\"$color\"/></a:$name>") }
            append("</a:clrScheme><a:fontScheme name=\"DocScan\"><a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>")
            append("<a:fmtScheme name=\"DocScan\"><a:fillStyleLst>${fill.repeat(3)}</a:fillStyleLst><a:lnStyleLst>")
            listOf(9525, 25400, 38100).forEach { append("<a:ln w=\"$it\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">$fill<a:prstDash val=\"solid\"/></a:ln>") }
            append("</a:lnStyleLst><a:effectStyleLst>")
            repeat(3) { append("<a:effectStyle><a:effectLst/></a:effectStyle>") }
            append("</a:effectStyleLst><a:bgFillStyleLst>${fill.repeat(3)}</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>")
        }
    }

    private fun contentTypes(overrides: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        overrides.forEach { (part, type) -> append("<Override PartName=\"$part\" ContentType=\"$type\"/>") }
        append("</Types>")
    }

    private fun rootRels(target: String): String = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$REL_PACKAGE"><Relationship Id="rId1" Type="$REL_OFFICE/officeDocument" Target="$target"/></Relationships>"""

    private fun columnName(index: Int): String {
        var number = index
        return buildString {
            while (number > 0) { number--; append(('A'.code + number % 26).toChar()); number /= 26 }
        }.reversed()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
