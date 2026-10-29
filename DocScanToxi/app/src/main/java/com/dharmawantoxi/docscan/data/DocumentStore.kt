package com.dharmawantoxi.docscan.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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

/**
 * Setiap dokumen disimpan dalam folder privat. Metadata ditulis secara atomik;
 * file halaman baru hanya dimasukkan ke metadata setelah semuanya berhasil disalin.
 * Panggil operasi ini dari Dispatchers.IO.
 */
object DocumentStore {
    const val MAX_PAGES = 25
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
        return Document(id, obj.getString("title"), obj.getLong("createdAt"), obj.getLong("updatedAt"), pages)
    }

    private fun write(context: Context, doc: Document) {
        val json = JSONObject().apply {
            put("id", doc.id)
            put("title", doc.title)
            put("createdAt", doc.createdAt)
            put("updatedAt", doc.updatedAt)
            put("pages", JSONArray(doc.pages))
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
        val result = old.copy(pages = old.pages - page, updatedAt = System.currentTimeMillis())
        write(context, result)
        pageFile(context, id, page).delete()
        result
    }

    fun replacePage(context: Context, id: String, page: String, bitmap: Bitmap): Document = synchronized(lock) {
        val old = read(context, id)
        require(page in old.pages) { "Halaman tidak ditemukan" }
        saveBitmap(pageFile(context, id, page), bitmap)
        old.copy(updatedAt = System.currentTimeMillis()).also { write(context, it) }
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        val dir = folder(context, id)
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Dokumen tidak dapat dihapus sepenuhnya")
    }
}
