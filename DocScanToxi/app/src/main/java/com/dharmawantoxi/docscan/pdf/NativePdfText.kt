package com.dharmawantoxi.docscan.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Satu operand teks PDF yang dapat diganti tanpa meraster halaman. */
data class PdfTextSegment(val operatorIndex: Int, val text: String)
data class PdfTextPage(val segments: List<PdfTextSegment>, val skipped: Int)
data class NativePdfInfo(val pageCount: Int)

/**
 * Mengubah operand Tj/TJ pada halaman, bukan menutup teks lama dengan kotak putih.
 * Posisi, warna, font, gambar, dan objek lain tetap berasal dari PDF. Teks dalam
 * XObject, optional content, font tertentu, dan properti ActualText dilewati.
 * File keluaran HARUS berbeda dari file masukan dan baru dipakai jika proses selesai.
 */
object NativePdfText {
    const val MAX_PAGES = 25
    private const val MAX_CONTENT_BYTES = 16 * 1024 * 1024
    private const val MAX_TOKENS = 250_000
    private const val MAX_SEGMENTS = 10_000
    private const val MAX_REPLACEMENT = 2000
    private val actualText = COSName.getPDFName("ActualText")
    private val optionalContent = COSName.getPDFName("OC")
    private val perms = COSName.getPDFName("Perms")
    private val signaturePermissions = listOf("DocMDP", "UR", "UR3", "FieldMDP").map(COSName::getPDFName)

    fun inspect(file: File): NativePdfInfo = load(file).use { document ->
        verifyEditable(document)
        NativePdfInfo(document.numberOfPages)
    }

    fun readPage(file: File, pageIndex: Int): PdfTextPage = load(file).use { document ->
        verifyEditable(document)
        val page = checkedPage(document, pageIndex)
        val parsed = parse(page)
        val collected = collect(parsed, page.resources)
        PdfTextPage(collected.candidates.map { PdfTextSegment(it.index, it.text) }, collected.skipped)
    }

    fun replace(file: File, output: File, pageIndex: Int, segment: PdfTextSegment, replacement: String) {
        require(file.canonicalFile != output.canonicalFile) { "File hasil harus berbeda dari file sumber" }
        require(replacement.length <= MAX_REPLACEMENT) { "Teks pengganti terlalu panjang (maksimal $MAX_REPLACEMENT karakter)" }
        require(!replacement.contains('\n') && !replacement.contains('\r')) { "Ganti satu segmen dalam satu baris" }
        var expectedPages = 0
        load(file).use { document ->
            verifyEditable(document)
            expectedPages = document.numberOfPages
            val page = checkedPage(document, pageIndex)
            val tokens = parse(page)
            val candidate = collect(tokens, page.resources).candidates.firstOrNull { it.index == segment.operatorIndex }
                ?: throw IOException("Segmen tidak lagi tersedia; muat ulang halaman")
            if (candidate.text != segment.text) throw IOException("Teks sudah berubah; muat ulang halaman")
            if (replacement == candidate.text) throw IOException("Teks pengganti sama dengan teks sebelumnya")
            val encoded = try { candidate.font.encode(replacement) }
            catch (e: Exception) { throw IOException("Font asli tidak mendukung sebagian karakter pengganti", e) }
            if (decode(candidate.font, encoded) != replacement) {
                throw IOException("Karakter pengganti tidak dapat dipetakan ke font asli")
            }
            // Satu TJ dapat berisi beberapa string dan penyesuaian kerning. Tulis
            // string pengganti sebagai satu operand; posisi awal dan font tetap.
            tokens[candidate.index - 1] = if (candidate.isArray) COSArray().apply { add(COSString(encoded)) }
                else COSString(encoded)
            val newStream = PDStream(document)
            newStream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
            page.setContents(newStream)
            document.save(output)
        }
        // Jangan pakai hasil yang tidak dapat dibaca lagi, kehilangan halaman,
        // atau tidak memuat penggantian yang diharapkan.
        load(output).use { result ->
            verifyEditable(result)
            if (result.numberOfPages != expectedPages) throw IOException("Jumlah halaman PDF berubah")
            val page = checkedPage(result, pageIndex)
            if (replacement.isNotBlank()) {
                val confirmed = collect(parse(page), page.resources).candidates.firstOrNull { it.index == segment.operatorIndex }
                if (confirmed?.text != replacement) throw IOException("Teks hasil tidak sesuai dengan penggantian")
            }
        }
    }

    private fun load(file: File): PDDocument = PDDocument.load(
        file, MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile)
    )

    private fun verifyEditable(document: PDDocument) {
        if (document.isEncrypted || !document.currentAccessPermission.canModify()) {
            throw IOException("PDF yang terenkripsi atau dibatasi untuk diedit tidak didukung")
        }
        val permissionDict = document.documentCatalog.cosObject.getCOSDictionary(perms)
        if (document.signatureDictionaries.isNotEmpty() || signaturePermissions.any { permissionDict?.getDictionaryObject(it) != null }) {
            throw IOException("PDF bertanda tangan digital tidak dapat diedit karena tanda tangan akan menjadi tidak valid")
        }
        if (document.numberOfPages !in 1..MAX_PAGES) {
            throw IOException("PDF harus berisi 1 sampai $MAX_PAGES halaman")
        }
    }

    private fun checkedPage(document: PDDocument, index: Int): PDPage {
        if (index !in 0 until document.numberOfPages) throw IOException("Halaman PDF tidak ditemukan")
        return document.getPage(index)
    }

    private fun parse(page: PDPage): MutableList<Any> {
        val bytes = ByteArrayOutputStream()
        page.contents.use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_CONTENT_BYTES) throw IOException("Isi halaman terlalu besar atau rumit untuk diedit")
                bytes.write(buffer, 0, count)
            }
        }
        val parser = PDFStreamParser(bytes.toByteArray())
        val tokens = mutableListOf<Any>()
        while (true) {
            val token = parser.parseNextToken() ?: break
            if (tokens.size >= MAX_TOKENS) throw IOException("Isi halaman terlalu rumit untuk diedit")
            tokens.add(token)
        }
        return tokens
    }

    private data class Candidate(val index: Int, val text: String, val font: PDFont, val isArray: Boolean)
    private data class CollectionResult(val candidates: List<Candidate>, val skipped: Int)
    private data class State(val font: PDFont?, val renderMode: Int, val fillAlpha: Float, val strokeAlpha: Float)

    private fun collect(tokens: List<Any>, resources: PDResources?): CollectionResult {
        val candidates = mutableListOf<Candidate>()
        var skipped = 0
        var font: PDFont? = null
        var renderMode = 0
        var fillAlpha = 1f
        var strokeAlpha = 1f
        var inText = false
        val graphicsStack = ArrayDeque<State>()
        val markedStack = ArrayDeque<Boolean>()
        tokens.forEachIndexed { index, token ->
            if (token !is Operator) return@forEachIndexed
            when (token.name) {
                "q" -> graphicsStack.addLast(State(font, renderMode, fillAlpha, strokeAlpha))
                "Q" -> {
                    val previous = if (graphicsStack.isEmpty()) null else graphicsStack.removeLast()
                    font = previous?.font
                    renderMode = previous?.renderMode ?: 0
                    fillAlpha = previous?.fillAlpha ?: 1f
                    strokeAlpha = previous?.strokeAlpha ?: 1f
                }
                "BT" -> inText = true
                "ET" -> inText = false
                "Tf" -> {
                    val fontName = tokens.getOrNull(index - 2) as? COSName
                    font = if (fontName == null) null else try { resources?.getFont(fontName) } catch (_: Exception) { null }
                }
                "gs" -> {
                    val stateName = tokens.getOrNull(index - 1) as? COSName
                    val extended = if (stateName == null) null else resources?.getExtGState(stateName)
                    if (extended == null) font = null
                    else {
                        if (extended.fontSetting != null) font = try { extended.fontSetting.font } catch (_: Exception) { null }
                        extended.nonStrokingAlphaConstant?.let { fillAlpha = it }
                        extended.strokingAlphaConstant?.let { strokeAlpha = it }
                    }
                }
                "Tr" -> renderMode = (tokens.getOrNull(index - 1) as? COSNumber)?.intValue() ?: -1
                // Optional content dapat disembunyikan; jangan ubah tanpa mengevaluasi layer.
                "BMC" -> markedStack.addLast(tokens.getOrNull(index - 1) == optionalContent)
                "BDC" -> markedStack.addLast(tokens.getOrNull(index - 2) == optionalContent ||
                    hasActualText(tokens.getOrNull(index - 1), resources))
                "EMC" -> if (markedStack.isNotEmpty()) markedStack.removeLast()
                "Tj", "TJ", "'", "\"" -> {
                    val currentFont = font
                    val operand = tokens.getOrNull(index - 1)
                    val visible = when (renderMode) {
                        0 -> fillAlpha > 0f
                        1 -> strokeAlpha > 0f
                        2 -> fillAlpha > 0f || strokeAlpha > 0f
                        else -> false
                    }
                    if (!inText || currentFont == null || currentFont.isVertical || !visible || markedStack.any { it }) {
                        skipped++
                        return@forEachIndexed
                    }
                    val isArray = token.name == "TJ"
                    val decoded = try { decodeOperand(currentFont, operand, isArray) } catch (_: Exception) { null }
                    if (decoded == null) { skipped++; return@forEachIndexed }
                    if (decoded.isBlank()) return@forEachIndexed
                    if (decoded.length > MAX_REPLACEMENT || decoded.contains('\n') || decoded.contains('\r')) {
                        skipped++
                        return@forEachIndexed
                    }
                    if (candidates.size >= MAX_SEGMENTS) throw IOException("Halaman memiliki terlalu banyak segmen teks")
                    candidates.add(Candidate(index, decoded, currentFont, isArray))
                }
            }
        }
        return CollectionResult(candidates, skipped)
    }

    private fun hasActualText(property: Any?, resources: PDResources?): Boolean {
        val dictionary: COSDictionary? = when (property) {
            is COSDictionary -> property
            is COSName -> resources?.getProperties(property)?.cosObject
            else -> null
        }
        // Jika properti tidak dapat dibaca, hindari merusak teks aksesibilitas.
        return dictionary == null || dictionary.containsKey(actualText)
    }

    private fun decodeOperand(font: PDFont, operand: Any?, isArray: Boolean): String? {
        if (!isArray) return (operand as? COSString)?.let { decode(font, it.bytes) }
        val array = operand as? COSArray ?: return null
        val result = StringBuilder()
        var found = false
        for (index in 0 until array.size()) {
            when (val item = array.getObject(index)) {
                is COSString -> { result.append(decode(font, item.bytes)); found = true }
                is COSNumber -> Unit // penyesuaian kerning, bukan karakter
                else -> return null
            }
        }
        return if (found) result.toString() else null
    }

    private fun decode(font: PDFont, bytes: ByteArray): String {
        val result = StringBuilder()
        val input = ByteArrayInputStream(bytes)
        while (input.available() > 0) {
            val before = input.available()
            val code = font.readCode(input)
            if (input.available() >= before) throw IOException("Kode font tidak valid")
            val unicode = font.toUnicode(code) ?: throw IOException("Teks tidak memiliki pemetaan Unicode")
            result.append(unicode)
        }
        return result.toString()
    }
}
