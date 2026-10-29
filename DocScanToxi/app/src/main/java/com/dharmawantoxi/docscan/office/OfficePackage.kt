package com.dharmawantoxi.docscan.office

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Hanya format Office Open XML modern yang didukung; format biner lama bukan ZIP OOXML. */
enum class OfficeFormat(val extension: String, val mime: String, val displayName: String) {
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
    POWERPOINT("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint")
}

data class OfficeSection(val part: String, val label: String)
enum class OfficeItemType { TEXT, NUMBER, BOOLEAN }
data class OfficeItem(val key: String, val text: String, val type: OfficeItemType = OfficeItemType.TEXT)
data class OfficeInfo(val format: OfficeFormat, val sections: List<OfficeSection>)

/**
 * Mengubah teks/cell di XML OOXML asli. Part lain disalin apa adanya, tidak
 * diratakan menjadi gambar. Fitur kompleks tidak direkonstruksi; lihat batasan UI.
 */
object OfficePackage {
    const val MAX_FILE_BYTES = 100L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    private const val MAX_PART_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 4000
    private const val MAX_SECTIONS = 100
    private const val MAX_ITEMS = 10000
    private const val MAX_TEXT = 32767
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CONTENT = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
    private const val ACCESS_EXTERNAL_STYLESHEET = "http://javax.xml.XMLConstants/property/accessExternalStylesheet"

    fun inspect(file: File): OfficeInfo = open(file).use { zip -> inspect(zip) }

    fun readSection(file: File, section: OfficeSection): List<OfficeItem> = open(file).use { zip ->
        val info = inspect(zip)
        val actual = info.sections.firstOrNull { it.part == section.part }
            ?: throw IOException("Bagian Office tidak ditemukan")
        val document = xml(zip, actual.part)
        items(zip, info.format, document)
    }

    fun replace(file: File, output: File, section: OfficeSection, selected: OfficeItem, replacement: String) {
        require(file.canonicalFile != output.canonicalFile) { "File hasil harus berbeda dari sumber" }
        require(replacement.length <= MAX_TEXT) { "Teks terlalu panjang (maksimal $MAX_TEXT karakter)" }
        require(!replacement.contains('\n') && !replacement.contains('\r')) { "Ganti satu segmen dalam satu baris" }
        checkXmlText(replacement)
        val format = open(file).use { zip ->
            val info = inspect(zip)
            if (info.sections.none { it.part == section.part }) throw IOException("Bagian Office tidak ditemukan")
            val document = xml(zip, section.part)
            val before = items(zip, info.format, document).firstOrNull { it.key == selected.key }
                ?: throw IOException("Teks atau sel tidak lagi tersedia; muat ulang")
            if (before != selected) throw IOException("Isi sudah berubah; muat ulang")
            if (replacement == before.text) throw IOException("Isi pengganti sama dengan sebelumnya")
            when (info.format) {
                OfficeFormat.WORD -> replaceTextNode(document, W, selected.key, replacement)
                OfficeFormat.POWERPOINT -> replaceTextNode(document, A, selected.key, replacement)
                OfficeFormat.EXCEL -> replaceCell(document, selected, replacement)
            }
            val changes = mutableMapOf(section.part to serialize(document))
            if (info.format == OfficeFormat.EXCEL) {
                // Aplikasi spreadsheet menghitung ulang formula saat dokumen dibuka.
                val workbook = xml(zip, "xl/workbook.xml")
                setRecalculation(workbook)
                changes["xl/workbook.xml"] = serialize(workbook)
            }
            rewrite(zip, output, changes)
            info.format
        }
        // Periksa kembali paket dan konten yang ditulis, bukan hanya nama file.
        open(output).use { zip ->
            val info = inspect(zip)
            if (info.format != format || info.sections.none { it.part == section.part }) {
                throw IOException("Struktur hasil Office tidak sesuai")
            }
            val actual = items(zip, format, xml(zip, section.part), includeBlank = true)
                .firstOrNull { it.key == selected.key }
            val expected = if (selected.type == OfficeItemType.BOOLEAN && replacement.isNotBlank()) {
                when (replacement.trim().uppercase()) { "1", "TRUE" -> "TRUE"; "0", "FALSE" -> "FALSE"; else -> replacement }
            } else if (selected.type == OfficeItemType.NUMBER) replacement.trim() else replacement
            if (actual?.text != expected) throw IOException("Isi hasil Office tidak sesuai dengan penggantian")
        }
    }

    private fun open(file: File): ZipFile {
        if (!file.isFile || file.length() == 0L || file.length() > MAX_FILE_BYTES) {
            throw IOException("File Office kosong atau melebihi 100 MB")
        }
        return try { ZipFile(file) } catch (e: Exception) {
            throw IOException("Gunakan file DOCX, XLSX, atau PPTX yang valid dan tidak terenkripsi", e)
        }
    }

    private fun inspect(zip: ZipFile): OfficeInfo {
        val seen = mutableSetOf<String>()
        var count = 0
        var total = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (++count > MAX_ENTRIES || !safePartName(name) || !seen.add(name)) {
                throw IOException("Struktur file Office terlalu rumit atau tidak aman")
            }
            if (entry.size < 0 || entry.size > MAX_FILE_BYTES) throw IOException("Bagian Office terlalu besar")
            total += entry.size
            if (total > MAX_TOTAL_BYTES) throw IOException("Isi file Office terlalu besar setelah dibuka")
            val lower = name.lowercase()
            if (lower.startsWith("_xmlsignatures/") || lower.endsWith("/vbaproject.bin")) {
                throw IOException("File Office bertanda tangan atau berisi makro tidak didukung")
            }
        }
        val content = xml(zip, "[Content_Types].xml")
        val overrides = elements(content, CONTENT, "Override")
        if (overrides.any { it.getAttribute("ContentType").contains("macroEnabled", ignoreCase = true) }) {
            throw IOException("File Office dengan makro tidak didukung")
        }
        val format = when {
            overrides.any { it.getAttribute("PartName") == "/word/document.xml" && it.getAttribute("ContentType") == "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml" } -> OfficeFormat.WORD
            overrides.any { it.getAttribute("PartName") == "/xl/workbook.xml" && it.getAttribute("ContentType") == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml" } -> OfficeFormat.EXCEL
            overrides.any { it.getAttribute("PartName") == "/ppt/presentation.xml" && it.getAttribute("ContentType") == "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml" } -> OfficeFormat.POWERPOINT
            else -> throw IOException("Format harus DOCX, XLSX, atau PPTX biasa; format lama dan template tidak didukung")
        }
        val sections = when (format) {
            OfficeFormat.WORD -> wordSections(zip, seen)
            OfficeFormat.EXCEL -> workbookSections(zip)
            OfficeFormat.POWERPOINT -> slideSections(zip)
        }
        if (sections.isEmpty() || sections.size > MAX_SECTIONS || sections.any { it.part !in seen }) {
            throw IOException("Jumlah atau struktur bagian Office tidak didukung")
        }
        if (sections.map { it.part }.distinct().size != sections.size) {
            throw IOException("Referensi bagian Office berulang tidak didukung")
        }
        return OfficeInfo(format, sections)
    }

    private fun wordSections(zip: ZipFile, names: Set<String>): List<OfficeSection> {
        val main = xml(zip, "word/document.xml")
        if (main.documentElement.namespaceURI != W) throw IOException("Varian Word ini belum didukung")
        if ("word/settings.xml" in names) {
            val settings = xml(zip, "word/settings.xml")
            val protected = elements(settings, W, "documentProtection").any {
                it.getAttributeNS(W, "enforcement").lowercase() in setOf("1", "true", "on")
            }
            if (protected) throw IOException("Dokumen Word yang dilindungi tidak dapat diedit")
        }
        val parts = mutableListOf(OfficeSection("word/document.xml", "Isi dokumen"))
        val relationships = if ("word/_rels/document.xml.rels" in names)
            relationships(zip, "word/_rels/document.xml.rels", "word/document.xml", "/header", "word/header") +
                relationships(zip, "word/_rels/document.xml.rels", "word/document.xml", "/footer", "word/footer")
        else emptyMap()
        relationships.entries.sortedBy { it.value }.forEach { (_, part) ->
            val label = part.substringAfter('/').removeSuffix(".xml")
                .replace("header", "Header ").replace("footer", "Footer ")
            parts.add(OfficeSection(part, label))
        }
        if ("word/footnotes.xml" in names) parts.add(OfficeSection("word/footnotes.xml", "Catatan kaki"))
        if ("word/endnotes.xml" in names) parts.add(OfficeSection("word/endnotes.xml", "Catatan akhir"))
        return parts
    }

    private fun workbookSections(zip: ZipFile): List<OfficeSection> {
        val workbook = xml(zip, "xl/workbook.xml")
        if (workbook.documentElement.namespaceURI != S) throw IOException("Varian Excel ini belum didukung")
        if (elements(workbook, S, "workbookProtection").any {
                it.getAttribute("lockStructure").lowercase() in setOf("1", "true", "on")
            }) throw IOException("Workbook Excel yang dilindungi tidak didukung")
        val relationships = relationships(zip, "xl/_rels/workbook.xml.rels", "xl/workbook.xml", "/worksheet", "xl/worksheets/")
        return elements(workbook, S, "sheet").mapNotNull { sheet ->
            val part = relationships[sheet.getAttributeNS(R, "id")] ?: return@mapNotNull null
            OfficeSection(part, sheet.getAttribute("name").ifBlank { "Lembar" }.take(100))
        }
    }

    private fun slideSections(zip: ZipFile): List<OfficeSection> {
        val presentation = xml(zip, "ppt/presentation.xml")
        if (presentation.documentElement.namespaceURI != P) throw IOException("Varian PowerPoint ini belum didukung")
        if (elements(presentation, P, "modifyVerifier").isNotEmpty()) throw IOException("Presentasi yang dilindungi tidak didukung")
        val relationships = relationships(zip, "ppt/_rels/presentation.xml.rels", "ppt/presentation.xml", "/slide", "ppt/slides/")
        return elements(presentation, P, "sldId").mapIndexedNotNull { index, slide ->
            val part = relationships[slide.getAttributeNS(R, "id")] ?: return@mapIndexedNotNull null
            OfficeSection(part, "Slide ${index + 1}")
        }
    }

    private fun relationships(zip: ZipFile, relPart: String, base: String, suffix: String,
                              prefix: String): Map<String, String> {
        val document = xml(zip, relPart)
        val result = mutableMapOf<String, String>()
        for (rel in elements(document, PKG_REL, "Relationship")) {
            if (!rel.getAttribute("Type").endsWith(suffix)) continue
            // Bagian eksternal tidak dapat diperiksa atau disunting dengan aman.
            if (rel.getAttribute("TargetMode").equals("External", true)) {
                throw IOException("Referensi eksternal dalam file Office tidak didukung")
            }
            val id = rel.getAttribute("Id")
            val part = resolvePart(base, rel.getAttribute("Target"))
            if (id.isBlank() || !part.startsWith(prefix) || !part.endsWith(".xml") || result.put(id, part) != null) {
                throw IOException("Referensi Office tidak aman atau tidak didukung")
            }
        }
        return result
    }

    private fun resolvePart(base: String, target: String): String {
        if (target.isBlank() || target.contains('\\') || target.contains('?') || target.contains('#')) {
            throw IOException("Referensi Office tidak valid")
        }
        val uri = try { URI(base).resolve(target).normalize() } catch (e: Exception) { throw IOException("Referensi Office tidak valid", e) }
        if (uri.isAbsolute || uri.authority != null) throw IOException("Referensi Office eksternal tidak didukung")
        val path = uri.path.removePrefix("/")
        if (!safePartName(path)) throw IOException("Referensi Office tidak aman")
        return path
    }

    private fun safePartName(name: String): Boolean = name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') &&
        name.split('/').none { it == ".." || it == "." } && !name.contains('\u0000')

    private fun items(zip: ZipFile, format: OfficeFormat, document: Document, includeBlank: Boolean = false): List<OfficeItem> {
        return when (format) {
            OfficeFormat.WORD -> textItems(document, W, includeBlank)
            OfficeFormat.POWERPOINT -> textItems(document, A, includeBlank)
            OfficeFormat.EXCEL -> cellItems(zip, document, includeBlank)
        }
    }

    private fun textItems(document: Document, namespace: String, includeBlank: Boolean): List<OfficeItem> {
        val nodes = elements(document, namespace, "t")
        if (nodes.size > MAX_ITEMS) throw IOException("Terlalu banyak segmen teks dalam bagian ini")
        return nodes.mapIndexedNotNull { index, node ->
            val value = node.textContent.orEmpty()
            if ((!includeBlank && value.isBlank()) || value.length > MAX_TEXT || value.contains('\n') || value.contains('\r')) null
            else OfficeItem(index.toString(), value)
        }
    }

    private fun replaceTextNode(document: Document, namespace: String, key: String, replacement: String) {
        val index = key.toIntOrNull() ?: throw IOException("Segmen teks tidak valid")
        val node = elements(document, namespace, "t").getOrNull(index) ?: throw IOException("Segmen teks tidak ditemukan")
        node.textContent = replacement
        node.setAttributeNS(XML, "xml:space", "preserve")
    }

    private fun cellItems(zip: ZipFile, document: Document, includeBlank: Boolean): List<OfficeItem> {
        if (elements(document, S, "sheetProtection").isNotEmpty()) throw IOException("Lembar Excel yang dilindungi tidak dapat diedit")
        val shared = if (zip.getEntry("xl/sharedStrings.xml") == null) emptyList() else sharedStrings(xml(zip, "xl/sharedStrings.xml"))
        val cells = elements(document, S, "c")
        if (cells.size > MAX_ITEMS) throw IOException("Terlalu banyak sel untuk editor ini")
        val seen = mutableSetOf<String>()
        return cells.mapNotNull { cell ->
            val address = cell.getAttribute("r").uppercase()
            if (!validAddress(address) || !seen.add(address)) throw IOException("Alamat sel Excel tidak valid atau berulang")
            if (directChildren(cell, S, "f").isNotEmpty()) return@mapNotNull null // Jangan ubah formula.
            val type = cell.getAttribute("t")
            val value = directChildren(cell, S, "v").firstOrNull()?.textContent.orEmpty()
            val item = when (type) {
                "s" -> value.toIntOrNull()?.let { shared.getOrNull(it) }?.let { OfficeItem(address, it) }
                "inlineStr" -> directChildren(cell, S, "is").firstOrNull()?.let { OfficeItem(address, stringContent(it)) }
                "str" -> OfficeItem(address, value)
                "b" -> when (value) { "1" -> OfficeItem(address, "TRUE", OfficeItemType.BOOLEAN); "0" -> OfficeItem(address, "FALSE", OfficeItemType.BOOLEAN); else -> null }
                "", "n" -> if (value.isNotEmpty() && isNumber(value)) OfficeItem(address, value, OfficeItemType.NUMBER) else null
                else -> null // Mis. error dan tanggal ISO.
            }
            if (item == null || item.text.length > MAX_TEXT || item.text.contains('\n') || item.text.contains('\r') || (!includeBlank && item.text.isBlank())) null else item
        }
    }

    private fun sharedStrings(document: Document): List<String> {
        val all = elements(document, S, "si")
        if (all.size > 100_000) throw IOException("Terlalu banyak string Excel")
        return all.map(::stringContent)
    }

    private fun stringContent(container: Element): String = buildString {
        var child = container.firstChild
        while (child != null) {
            if (child is Element && child.namespaceURI == S) {
                when (child.localName) {
                    "t" -> append(child.textContent.orEmpty())
                    "r" -> directChildren(child, S, "t").forEach { append(it.textContent.orEmpty()) }
                }
            }
            child = child.nextSibling
        }
    }

    private fun replaceCell(document: Document, selected: OfficeItem, replacement: String) {
        val cell = elements(document, S, "c").firstOrNull { it.getAttribute("r").equals(selected.key, true) }
            ?: throw IOException("Sel tidak ditemukan")
        val kind = if (replacement.isEmpty()) OfficeItemType.TEXT else selected.type
        val value = when (kind) {
            OfficeItemType.TEXT -> replacement
            OfficeItemType.NUMBER -> replacement.trim().also { if (!isNumber(it)) throw IOException("Masukkan angka yang valid, misalnya 123.45") }
            OfficeItemType.BOOLEAN -> when (replacement.trim().uppercase()) {
                "TRUE", "1" -> "1"
                "FALSE", "0" -> "0"
                else -> throw IOException("Masukkan TRUE atau FALSE")
            }
        }
        directChildren(cell, S, "v").plus(directChildren(cell, S, "is")).forEach { cell.removeChild(it) }
        val prefix = cell.prefix?.let { "$it:" }.orEmpty()
        val child = if (kind == OfficeItemType.TEXT) {
            cell.setAttribute("t", "inlineStr")
            document.createElementNS(S, "${prefix}is").apply {
                appendChild(document.createElementNS(S, "${prefix}t").apply {
                    setAttributeNS(XML, "xml:space", "preserve")
                    textContent = value
                })
            }
        } else {
            cell.setAttribute("t", if (kind == OfficeItemType.NUMBER) "n" else "b")
            document.createElementNS(S, "${prefix}v").apply { textContent = value }
        }
        val extension = directChildren(cell, S, "extLst").firstOrNull()
        if (extension == null) cell.appendChild(child) else cell.insertBefore(child, extension)
    }

    private fun setRecalculation(document: Document) {
        val root = document.documentElement
        var calc = directChildren(root, S, "calcPr").firstOrNull()
        if (calc == null) {
            val prefix = root.prefix?.let { "$it:" }.orEmpty()
            calc = document.createElementNS(S, "${prefix}calcPr")
            // calcPr precedes later optional workbook elements, including extLst.
            val following = directChildren(root, S, "oleSize").firstOrNull()
                ?: directChildren(root, S, "customWorkbookViews").firstOrNull()
                ?: directChildren(root, S, "pivotCaches").firstOrNull()
                ?: directChildren(root, S, "smartTagPr").firstOrNull()
                ?: directChildren(root, S, "smartTagTypes").firstOrNull()
                ?: directChildren(root, S, "webPublishing").firstOrNull()
                ?: directChildren(root, S, "fileRecoveryPr").firstOrNull()
                ?: directChildren(root, S, "webPublishObjects").firstOrNull()
                ?: directChildren(root, S, "extLst").firstOrNull()
            if (following == null) root.appendChild(calc) else root.insertBefore(calc, following)
        }
        calc.setAttribute("fullCalcOnLoad", "1")
        calc.setAttribute("forceFullCalc", "1")
    }

    private fun validAddress(value: String): Boolean {
        val match = Regex("([A-Z]{1,3})([1-9][0-9]{0,6})").matchEntire(value) ?: return false
        val col = match.groupValues[1].fold(0) { n, c -> n * 26 + (c - 'A' + 1) }
        val row = match.groupValues[2].toIntOrNull() ?: return false
        return col in 1..16384 && row in 1..1_048_576
    }

    private fun isNumber(value: String): Boolean = try {
        Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").matches(value) &&
            value.length <= 128 && BigDecimal(value).toDouble().isFinite()
    } catch (_: Exception) { false }

    private fun elements(document: Document, namespace: String, local: String): List<Element> {
        val nodes = document.getElementsByTagNameNS(namespace, local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun directChildren(parent: Element, namespace: String, local: String): List<Element> {
        val result = mutableListOf<Element>()
        var child = parent.firstChild
        while (child != null) {
            if (child is Element && child.namespaceURI == namespace && child.localName == local) result.add(child)
            child = child.nextSibling
        }
        return result
    }

    private fun xml(zip: ZipFile, name: String): Document {
        val entry = zip.getEntry(name) ?: throw IOException("Bagian Office tidak ditemukan: $name")
        if (entry.isDirectory || entry.size < 0 || entry.size > MAX_PART_BYTES) throw IOException("Bagian XML Office terlalu besar")
        val bytes = zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (out.size() + n > MAX_PART_BYTES) throw IOException("Bagian XML Office terlalu besar")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        // Larang DTD/entity bahkan pada parser yang tidak mendukung setFeature.
        // Abaikan byte nol agar pemeriksaan juga mencakup XML UTF-16.
        val forbidden = listOf("<!DOCTYPE", "<!ENTITY")
        val matched = IntArray(forbidden.size)
        var tags = 0
        bytes.forEach { byte ->
            val raw = byte.toInt() and 0xff
            if (raw != 0) {
                val c = if (raw in 97..122) raw - 32 else raw
                if (c == '<'.code && ++tags > 100_000) throw IOException("XML Office terlalu rumit")
                forbidden.forEachIndexed { index, pattern ->
                    val next = if (c == pattern[matched[index]].code) matched[index] + 1 else if (c == '<'.code) 1 else 0
                    if (next == pattern.length) throw IOException("DTD XML tidak didukung")
                    matched[index] = next
                }
            }
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                // Android tidak menyediakan konstanta XMLConstants untuk properti ini.
                runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
                runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
            }
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SAXException("External XML entity tidak didukung") }
            }.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (e: Exception) { throw IOException("XML Office tidak dapat dibaca", e) }
    }

    private fun serialize(document: Document): ByteArray = try {
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_STYLESHEET, "") }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        ByteArrayOutputStream().also { transformer.transform(DOMSource(document), StreamResult(it)) }.toByteArray()
            .also { if (it.size > MAX_PART_BYTES) throw IOException("Bagian XML hasil terlalu besar") }
    } catch (e: Exception) { throw IOException("XML hasil Office tidak dapat ditulis", e) }

    private fun rewrite(zip: ZipFile, output: File, changes: Map<String, ByteArray>) {
        var total = 0L
        ZipOutputStream(output.outputStream().buffered()).use { out ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                out.putNextEntry(ZipEntry(entry.name).apply { if (entry.time >= 0) time = entry.time })
                val replacement = changes[entry.name]
                if (replacement != null) {
                    total += replacement.size
                    if (total > MAX_TOTAL_BYTES) throw IOException("Isi hasil Office terlalu besar")
                    out.write(replacement)
                } else if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var partSize = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            partSize += n
                            total += n
                            if (partSize > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) throw IOException("Isi hasil Office terlalu besar")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                out.closeEntry()
            }
        }
    }

    internal fun checkXmlText(text: String) {
        var offset = 0
        while (offset < text.length) {
            val code = text.codePointAt(offset)
            if (code != 0x9 && code != 0xA && code != 0xD && code !in 0x20..0xD7FF && code !in 0xE000..0xFFFD && code !in 0x10000..0x10FFFF) {
                throw IOException("Teks berisi karakter yang tidak didukung Office")
            }
            offset += Character.charCount(code)
        }
    }
}
