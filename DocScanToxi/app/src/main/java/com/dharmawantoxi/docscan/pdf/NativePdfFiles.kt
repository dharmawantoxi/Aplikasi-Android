package com.dharmawantoxi.docscan.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class NativePdfSession internal constructor(
    val id: String,
    val directory: File,
    val sourceUri: Uri,
    val title: String,
    val pageCount: Int,
    var sourceHash: String,
    var changed: Boolean,
    var unsaved: Boolean,
    var sourceUncertain: Boolean = false
) {
    val original: File get() = File(directory, "original.pdf")
    val working: File get() = File(directory, "working.pdf")
    internal var backupReady: Boolean = false
    internal var backupUri: Uri? = null
}

/** File asli sudah terverifikasi ditulis, tetapi sesi lokal perlu dibuka ulang. */
class CommittedPdfSessionException(cause: Throwable) : IOException(
    "File asli sudah berhasil diganti, tetapi sesi tidak dapat diperbarui. Tutup dan buka ulang PDF.", cause
)

/** File kerja privat, verifikasi hasil, dan penggantian sumber dengan cadangan. Panggil di IO. */
object NativePdfFiles {
    private const val MAX_BYTES = 100L * 1024 * 1024
    private val sessionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private fun root(context: Context) = File(context.cacheDir, "native-pdf-sessions").apply { mkdirs() }

    fun open(context: Context, uri: Uri): NativePdfSession {
        val id = UUID.randomUUID().toString()
        val dir = File(root(context), id)
        if (!dir.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
        try {
            val original = File(dir, "original.pdf")
            val digest = MessageDigest.getInstance("SHA-256")
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("PDF tidak dapat dibuka")
            source.use { input ->
                original.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_BYTES) throw IOException("Ukuran PDF melebihi 100 MB")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    if (total == 0L) throw IOException("File PDF kosong")
                }
            }
            val info = NativePdfText.inspect(original)
            // PdfRenderer juga harus dapat membuka PDF untuk pratinjau di perangkat.
            checkRenderable(original, info.pageCount, 0)
            original.copyTo(File(dir, "working.pdf"))
            return NativePdfSession(id, dir, uri, displayTitle(context, uri), info.pageCount,
                digest.digest().hex(), changed = false, unsaved = false).also { writeState(it) }
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
    }

    fun restore(context: Context, id: String): NativePdfSession? {
        if (!sessionPattern.matches(id)) return null
        return try {
            val dir = File(root(context), id)
            val json = AtomicFile(File(dir, "state.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
            val hash = json.getString("sourceHash")
            if (!hashPattern.matches(hash)) return null
            val session = NativePdfSession(id, dir, Uri.parse(json.getString("sourceUri")), json.getString("title"),
                json.getInt("pageCount"), hash, json.getBoolean("changed"), json.getBoolean("unsaved"),
                json.optBoolean("sourceUncertain", false))
            // Pulihkan transaksi yang terhenti sebelum file dibuka langsung oleh PDFBox.
            AtomicFile(session.original).openRead().use { }
            AtomicFile(session.working).openRead().use { }
            if (!session.original.isFile || !session.working.isFile || session.pageCount !in 1..NativePdfText.MAX_PAGES) return null
            // Proses bisa terhenti setelah file luar ditulis, tetapi sebelum status
            // sesi diperbarui. Jangan mengklaim file asli masih sama tanpa memeriksa.
            val localMatches = runCatching { VerifiedPdfOutput.hashFile(session.original) == hash }.getOrDefault(false)
            val sourceMatches = runCatching {
                VerifiedPdfOutput.hashDestination { context.contentResolver.openInputStream(session.sourceUri) } == hash
            }.getOrDefault(false)
            if (!localMatches || !sourceMatches) {
                session.sourceUncertain = true
                runCatching { writeState(session) }
            }
            session
        } catch (_: Exception) { null }
    }

    fun replaceText(session: NativePdfSession, page: Int, segment: PdfTextSegment, replacement: String) {
        val temporary = File.createTempFile("updated-", ".pdf", session.directory)
        try {
            NativePdfText.replace(session.working, temporary, page, segment, replacement)
            checkRenderable(temporary, session.pageCount, page)
            // Tulis penanda sebelum file kerja agar pemulihan setelah proses mati tidak
            // menganggap perubahan yang berhasil sebagai sudah tersimpan.
            val previousChanged = session.changed
            val previousUnsaved = session.unsaved
            session.changed = true
            session.unsaved = true
            session.backupReady = false
            session.backupUri = null
            try {
                writeState(session)
            } catch (e: Exception) {
                session.changed = previousChanged
                session.unsaved = previousUnsaved
                runCatching { writeState(session) }
                throw e
            }
            // Jika commit file kerja gagal, status tetap belum tersimpan karena
            // hasil akhir AtomicFile mungkin tidak dapat dipastikan.
            replaceLocal(session.working, temporary)
        } finally { temporary.delete() }
    }

    fun saveCopy(context: Context, session: NativePdfSession, destination: Uri) {
        if (sameDocument(context, session.sourceUri, destination)) {
            throw IOException("Itu file asli. Gunakan tombol Ganti file asli agar cadangan dibuat terlebih dahulu.")
        }
        val hash = VerifiedPdfOutput.hashFile(session.working)
        writeAndVerify(context, session.working, destination, hash)
        session.unsaved = false
        runCatching { writeState(session) }
    }

    fun saveBackup(context: Context, session: NativePdfSession, destination: Uri) {
        session.backupReady = false
        session.backupUri = null
        if (sameDocument(context, session.sourceUri, destination)) {
            throw IOException("Cadangan harus disimpan sebagai file berbeda dari PDF asli")
        }
        writeAndVerify(context, session.original, destination, session.sourceHash)
        session.backupUri = destination
        session.backupReady = true
    }

    /** Panggil hanya setelah pengguna menyimpan cadangan terpisah dan menyetujui penggantian. */
    fun overwriteOriginal(context: Context, session: NativePdfSession) {
        if (session.sourceUncertain) throw IOException("Kondisi file asli tidak pasti. Periksa cadangan dan buka ulang PDF.")
        if (!session.changed) throw IOException("Belum ada perubahan pada PDF")
        val backup = session.backupUri
        if (!session.backupReady || backup == null) throw IOException("Simpan cadangan PDF yang terpisah terlebih dahulu")
        session.backupReady = false
        session.backupUri = null
        if (sameDocument(context, session.sourceUri, backup) ||
            VerifiedPdfOutput.hashDestination { context.contentResolver.openInputStream(backup) } != session.sourceHash) {
            throw IOException("Cadangan berubah atau tidak terpisah dari file asli. Simpan cadangan baru.")
        }
        val newHash = try {
            VerifiedPdfOutput.replaceWithRecovery(session.original, session.working, session.sourceHash,
                { context.contentResolver.openInputStream(session.sourceUri) },
                { context.contentResolver.openOutputStream(session.sourceUri, "wt") })
        } catch (failure: PdfOverwriteException) {
            if (!failure.restored) {
                session.sourceUncertain = true
                runCatching { writeState(session) }
            }
            throw failure
        }
        try {
            replaceLocal(session.original, session.working)
            session.sourceHash = newHash
            session.changed = false
            session.unsaved = false
            session.sourceUncertain = false
            writeState(session)
        } catch (failure: Exception) {
            // File luar sudah berhasil diverifikasi; sesi tidak aman dipakai kembali.
            throw CommittedPdfSessionException(failure)
        }
    }

    fun discard(session: NativePdfSession) { session.directory.deleteRecursively() }

    private fun checkRenderable(file: File, expectedPages: Int, page: Int) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(fd) } catch (e: Exception) { runCatching { fd.close() }; throw e }
        renderer.use { pdf ->
            if (pdf.pageCount != expectedPages) throw IOException("Jumlah halaman PDF berubah")
            pdf.openPage(page).use { rendered ->
                if (rendered.width <= 0 || rendered.height <= 0) throw IOException("Ukuran halaman PDF tidak valid")
            }
        }
    }

    private fun replaceLocal(target: File, source: File) {
        require(target.canonicalFile != source.canonicalFile) { "File kerja harus berbeda dari sumber" }
        val expectedHash = VerifiedPdfOutput.hashFile(source)
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        var finished = false
        try {
            stream = atomic.startWrite()
            source.inputStream().use { it.copyTo(stream) }
            atomic.finishWrite(stream)
            finished = true
            // finishWrite dapat melaporkan kegagalan rename hanya lewat log Android.
            atomic.openRead().use { }
            if (VerifiedPdfOutput.hashFile(target) != expectedHash) throw IOException("File kerja tidak tersimpan dengan benar")
        } catch (failure: Exception) {
            if (!finished && stream != null) atomic.failWrite(stream)
            throw failure
        }
    }

    private fun writeAndVerify(context: Context, source: File, destination: Uri, expectedHash: String) {
        VerifiedPdfOutput.copyAndVerify(source, expectedHash,
            { context.contentResolver.openInputStream(destination) },
            { context.contentResolver.openOutputStream(destination, "wt") })
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sameDocument(context: Context, first: Uri, second: Uri): Boolean {
        if (first == second) return true
        // Provider dapat mengembalikan URI berbeda untuk dokumen yang sama.
        val canonicalFirst = runCatching { context.contentResolver.canonicalize(first) }.getOrNull()
        val canonicalSecond = runCatching { context.contentResolver.canonicalize(second) }.getOrNull()
        if (canonicalFirst != null && canonicalFirst == canonicalSecond) return true
        if (first.authority != second.authority) return false
        return try {
            DocumentsContract.isDocumentUri(context, first) && DocumentsContract.isDocumentUri(context, second) &&
                DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
        } catch (_: Exception) { false }
    }

    private fun displayTitle(context: Context, uri: Uri): String {
        val displayName = if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() else uri.lastPathSegment
        return displayName.orEmpty().removeSuffix(".pdf").removeSuffix(".PDF").trim().take(72).ifBlank { "PDF" }
    }

    private fun writeState(session: NativePdfSession) {
        val json = JSONObject().apply {
            put("sourceUri", session.sourceUri.toString())
            put("title", session.title)
            put("pageCount", session.pageCount)
            put("sourceHash", session.sourceHash)
            put("changed", session.changed)
            put("unsaved", session.unsaved)
            put("sourceUncertain", session.sourceUncertain)
        }
        val atomic = AtomicFile(File(session.directory, "state.json"))
        val serialized = json.toString()
        var stream: FileOutputStream? = null
        var finished = false
        try {
            stream = atomic.startWrite()
            stream.write(serialized.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            finished = true
            val stored = atomic.openRead().bufferedReader().use { it.readText() }
            if (stored != serialized) throw IOException("Status sesi PDF tidak tersimpan dengan benar")
        } catch (failure: Exception) {
            if (!finished && stream != null) atomic.failWrite(stream)
            throw failure
        }
    }
}
