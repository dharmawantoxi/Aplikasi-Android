package com.dharmawantoxi.docscan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Setiap dokumen disimpan dalam folder privat. Metadata ditulis secara atomik;
 * file halaman baru hanya dimasukkan ke metadata setelah semuanya berhasil disalin.
 * Panggil operasi ini dari Dispatchers.IO.
 */
object DocumentStore {
    const val MAX_PAGES = 25
    const val MAX_PDF_BYTES = 100L * 1024 * 1024
    private const val PDF_RENDER_MAX_SIDE = 2400
    private val lock = Any()
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val pagePattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")

    private fun root(context: Context) = File(context.filesDir, "documents").apply { mkdirs() }
    private fun folder(context: Context, id: String): File {
        require(idPattern.matches(id)) { "ID dokumen tidak valid" }
        return File(root(context), id)
    }
    fun pageFile(context: Context, id: String, page: String): File {
        require(pagePattern.matches(page)) { "ID halaman tidak valid" }
        return File(folder(context, id), page)
    }

    fun list(context: Context): List<Document> = synchronized(lock) {
        root(context).listFiles().orEmpty().filter { it.isDirectory && idPattern.matches(it.name) }
            .mapNotNull { runCatching { read(context, it.name) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    fun get(context: Context, id: String): Document? = synchronized(lock) {
        runCatching { read(context, id) }.getOrNull()
    }

    private fun read(context: Context, id: String): Document {
        val atomic = AtomicFile(File(folder(context, id), "document.json"))
        val obj = atomic.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        if (obj.getString("id") != id) throw IOException("Metadata dokumen tidak valid")
        val array = obj.getJSONArray("pages")
        if (array.length() !in 1..MAX_PAGES) throw IOException("Jumlah halaman tidak valid")
        val pages = (0 until array.length()).map { array.getString(it) }
        if (pages.any { !pagePattern.matches(it) } || pages.distinct().size != pages.size) {
            throw IOException("Metadata halaman tidak valid")
        }
        val formatJson = obj.optJSONObject("pageFormats")
        val formats = pages.mapNotNull { page ->
            val entry = formatJson?.optJSONObject(page) ?: return@mapNotNull null
            val width = entry.optInt("width", 0)
            val height = entry.optInt("height", 0)
            if (width in 1..PdfPageFormat.MAX_POINTS && height in 1..PdfPageFormat.MAX_POINTS)
                page to PdfPageFormat(width, height) else null
        }.toMap()
        return Document(id, obj.getString("title"), obj.getLong("createdAt"), obj.getLong("updatedAt"), pages, formats)
    }

    private fun write(context: Context, doc: Document) {
        val json = JSONObject().apply {
            put("id", doc.id)
            put("title", doc.title)
            put("createdAt", doc.createdAt)
            put("updatedAt", doc.updatedAt)
            put("pages", JSONArray(doc.pages))
            if (doc.pageFormats.isNotEmpty()) {
                val formats = JSONObject()
                doc.pageFormats.filterKeys { it in doc.pages }.forEach { (page, format) ->
                    formats.put(page, JSONObject().put("width", format.width).put("height", format.height))
                }
                put("pageFormats", formats)
            }
        }
        val atomic = AtomicFile(File(folder(context, doc.id), "document.json"))
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) atomic.failWrite(stream)
            throw e
        }
    }

    private fun saveBitmap(target: File, bitmap: Bitmap) {
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw IOException("Gagal menyimpan gambar")
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) atomic.failWrite(stream)
            throw e
        }
    }

    private fun copyPages(context: Context, id: String, uris: List<Uri>): List<String> {
        val written = mutableListOf<String>()
        try {
            uris.forEach { uri ->
                val name = "${UUID.randomUUID()}.jpg"
                val file = pageFile(context, id, name)
                val bitmap = ImageFiles.decodeImport(context, uri)
                try { saveBitmap(file, bitmap) } finally { bitmap.recycle() }
                written.add(name)
            }
            return written
        } catch (e: Exception) {
            written.forEach { pageFile(context, id, it).delete() }
            throw e
        }
    }

    fun create(context: Context, uris: List<Uri>): Document = synchronized(lock) {
        require(uris.size in 1..MAX_PAGES) { "Pilih 1 sampai $MAX_PAGES halaman" }
        val id = UUID.randomUUID().toString()
        val dir = folder(context, id)
        if (!dir.mkdirs()) throw IOException("Folder dokumen tidak dapat dibuat")
        try {
            val pages = copyPages(context, id, uris)
            val now = System.currentTimeMillis()
            val date = SimpleDateFormat("dd MMM yyyy HH.mm", Locale("id", "ID")).format(Date(now))
            Document(id, "Pindai $date", now, now, pages).also { write(context, it) }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * Impor PDF sebagai dokumen baru. Sumber disalin ke cache terlebih dahulu
     * karena PdfRenderer membutuhkan file yang bisa di-seek (provider cloud bisa berupa pipe).
     * Tiap halaman dirender satu per satu agar memori tetap terbatas.
     */
    fun importPdf(context: Context, uri: Uri): Document = synchronized(lock) {
        val temporary = File.createTempFile("import-pdf-", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("PDF tidak dapat dibuka")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > MAX_PDF_BYTES) throw IOException("Ukuran PDF melebihi 100 MB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val fd = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(fd) } catch (e: Exception) { runCatching { fd.close() }; throw e }
            renderer.use { pdf ->
                val pageCount = pdf.pageCount
                if (pageCount == 0) throw IOException("PDF tidak memiliki halaman")
                if (pageCount > MAX_PAGES) {
                    throw IOException("PDF berisi $pageCount halaman; maksimal $MAX_PAGES halaman")
                }
                val id = UUID.randomUUID().toString()
                val dir = folder(context, id)
                if (!dir.mkdirs()) throw IOException("Folder dokumen tidak dapat dibuat")
                try {
                    val pages = mutableListOf<String>()
                    val formats = mutableMapOf<String, PdfPageFormat>()
                    for (index in 0 until pageCount) {
                        pdf.openPage(index).use { sourcePage ->
                            val width = sourcePage.width
                            val height = sourcePage.height
                            if (width !in 1..PdfPageFormat.MAX_POINTS || height !in 1..PdfPageFormat.MAX_POINTS) {
                                throw IOException("Ukuran halaman ${index + 1} tidak didukung")
                            }
                            val scale = PDF_RENDER_MAX_SIDE.toFloat() / max(width, height)
                            val bitmap = Bitmap.createBitmap(
                                (width * scale).roundToInt().coerceAtLeast(1),
                                (height * scale).roundToInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val page = "${UUID.randomUUID()}.jpg"
                                saveBitmap(pageFile(context, id, page), bitmap)
                                pages.add(page)
                                formats[page] = PdfPageFormat(width, height)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    val now = System.currentTimeMillis()
                    Document(id, pdfTitle(context, uri), now, now, pages, formats).also { write(context, it) }
                } catch (failure: Throwable) {
                    dir.deleteRecursively()
                    throw failure
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun pdfTitle(context: Context, uri: Uri): String {
        val name = if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() else uri.lastPathSegment
        val base = name.orEmpty().let { if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it }
            .trim().ifBlank { "PDF" }
        // Sisakan ruang untuk penanda agar nama ekspor awal berbeda dari file sumber.
        return DocumentNames.displayName("${base.take(72).trimEnd()} (edit)")
    }

    fun addPages(context: Context, id: String, uris: List<Uri>): Document = synchronized(lock) {
        val old = read(context, id)
        require(uris.isNotEmpty() && old.pages.size + uris.size <= MAX_PAGES) {
            "Maksimal $MAX_PAGES halaman per dokumen"
        }
        val added = copyPages(context, id, uris)
        try {
            old.copy(updatedAt = System.currentTimeMillis(), pages = old.pages + added).also { write(context, it) }
        } catch (e: Exception) {
            added.forEach { pageFile(context, id, it).delete() }
            throw e
        }
    }

    fun rename(context: Context, id: String, value: String): Document = synchronized(lock) {
        val old = read(context, id)
        old.copy(title = DocumentNames.displayName(value), updatedAt = System.currentTimeMillis())
            .also { write(context, it) }
    }

    fun movePage(context: Context, id: String, page: String, direction: Int): Document = synchronized(lock) {
        val old = read(context, id)
        val from = old.pages.indexOf(page)
        val to = from + direction
        require(from >= 0 && direction in listOf(-1, 1) && to in old.pages.indices)
        val pages = old.pages.toMutableList().apply { add(to, removeAt(from)) }
        old.copy(pages = pages, updatedAt = System.currentTimeMillis()).also { write(context, it) }
    }

    fun deletePage(context: Context, id: String, page: String): Document = synchronized(lock) {
        val old = read(context, id)
        require(page in old.pages && old.pages.size > 1) { "Dokumen harus memiliki minimal satu halaman" }
        val result = old.copy(pages = old.pages - page, pageFormats = old.pageFormats - page,
            updatedAt = System.currentTimeMillis())
        write(context, result)
        pageFile(context, id, page).delete()
        result
    }

    fun replacePage(context: Context, id: String, page: String, bitmap: Bitmap): Document = synchronized(lock) {
        val old = read(context, id)
        require(page in old.pages) { "Halaman tidak ditemukan" }
        val file = pageFile(context, id, page)
        val formats = old.pageFormats.toMutableMap()
        formats[page]?.let { format ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                formats[page] = PdfGeometry.editedPageFormat(format, bounds.outWidth, bounds.outHeight,
                    bitmap.width, bitmap.height)
            }
        }
        saveBitmap(file, bitmap)
        old.copy(updatedAt = System.currentTimeMillis(), pageFormats = formats).also { write(context, it) }
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        val dir = folder(context, id)
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Dokumen tidak dapat dihapus sepenuhnya")
    }
}
