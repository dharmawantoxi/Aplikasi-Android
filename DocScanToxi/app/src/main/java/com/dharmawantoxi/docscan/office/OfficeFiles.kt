package com.dharmawantoxi.docscan.office

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.dharmawantoxi.docscan.pdf.PdfOverwriteException
import com.dharmawantoxi.docscan.pdf.VerifiedPdfOutput
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class OfficeSession internal constructor(
    val id: String,
    val directory: File,
    val sourceUri: Uri,
    val title: String,
    val format: OfficeFormat,
    var sourceHash: String,
    var changed: Boolean,
    var unsaved: Boolean,
    var sourceUncertain: Boolean = false
) {
    val original: File get() = File(directory, "original.${format.extension}")
    val working: File get() = File(directory, "working.${format.extension}")
    internal var backupUri: Uri? = null
}

class CommittedOfficeSessionException(cause: Throwable) : IOException(
    "File asli sudah berhasil diganti, tetapi sesi tidak dapat diperbarui. Tutup dan buka ulang file Office.", cause
)

/** Berkas kerja privat; file sumber hanya ditulis setelah cadangan dan konfirmasi. */
object OfficeFiles {
    private val sessionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private fun root(context: Context) = File(context.cacheDir, "office-sessions").apply { mkdirs() }

    fun open(context: Context, uri: Uri): OfficeSession {
        val id = UUID.randomUUID().toString()
        val dir = File(root(context), id)
        if (!dir.mkdirs()) throw IOException("Folder kerja Office tidak dapat dibuat")
        val inputCopy = File(dir, "input.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("File Office tidak dapat dibuka")
            input.use { source ->
                inputCopy.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        total += n
                        if (total > OfficePackage.MAX_FILE_BYTES) throw IOException("File Office melebihi 100 MB")
                        output.write(buffer, 0, n)
                    }
                    if (total == 0L) throw IOException("File Office kosong")
                }
            }
            val info = OfficePackage.inspect(inputCopy)
            val title = displayTitle(context, uri, info.format)
            val hash = VerifiedPdfOutput.hashFile(inputCopy)
            val session = OfficeSession(id, dir, uri, title, info.format, hash, changed = false, unsaved = false)
            inputCopy.copyTo(session.original)
            inputCopy.copyTo(session.working)
            writeState(session)
            return session
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        } finally { inputCopy.delete() }
    }

    fun restore(context: Context, id: String): OfficeSession? {
        if (!sessionPattern.matches(id)) return null
        return try {
            val dir = File(root(context), id)
            val json = AtomicFile(File(dir, "state.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
            val hash = json.getString("sourceHash")
            if (!hashPattern.matches(hash)) return null
            val session = OfficeSession(id, dir, Uri.parse(json.getString("sourceUri")), json.getString("title"),
                OfficeFormat.valueOf(json.getString("format")), hash, json.getBoolean("changed"), json.getBoolean("unsaved"),
                json.optBoolean("sourceUncertain", false))
            AtomicFile(session.original).openRead().use { }
            AtomicFile(session.working).openRead().use { }
            if (OfficePackage.inspect(session.working).format != session.format) return null
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

    fun replace(session: OfficeSession, section: OfficeSection, item: OfficeItem, replacement: String) {
        val temporary = File.createTempFile("updated-", ".${session.format.extension}", session.directory)
        try {
            OfficePackage.replace(session.working, temporary, section, item, replacement)
            val oldChanged = session.changed
            val oldUnsaved = session.unsaved
            session.changed = true
            session.unsaved = true
            session.backupUri = null
            try { writeState(session) } catch (failure: Exception) {
                session.changed = oldChanged
                session.unsaved = oldUnsaved
                runCatching { writeState(session) }
                throw failure
            }
            replaceLocal(session.working, temporary)
        } finally { temporary.delete() }
    }

    fun saveCopy(context: Context, session: OfficeSession, destination: Uri) {
        if (sameDocument(context, session.sourceUri, destination)) {
            throw IOException("Itu file asli. Gunakan Ganti file asli agar cadangan dibuat terlebih dahulu.")
        }
        writeAndVerify(context, session.working, destination, VerifiedPdfOutput.hashFile(session.working))
        session.unsaved = false
        runCatching { writeState(session) }
    }

    fun saveBackup(context: Context, session: OfficeSession, destination: Uri) {
        session.backupUri = null
        if (sameDocument(context, session.sourceUri, destination)) throw IOException("Cadangan harus berupa file terpisah")
        writeAndVerify(context, session.original, destination, session.sourceHash)
        session.backupUri = destination
    }

    fun overwriteOriginal(context: Context, session: OfficeSession) {
        if (session.sourceUncertain) throw IOException("Kondisi file asli tidak pasti. Periksa cadangan dan buka ulang.")
        if (!session.changed) throw IOException("Belum ada perubahan")
        val backup = session.backupUri ?: throw IOException("Simpan cadangan terpisah terlebih dahulu")
        session.backupUri = null
        if (sameDocument(context, session.sourceUri, backup) ||
            VerifiedPdfOutput.hashDestination { context.contentResolver.openInputStream(backup) } != session.sourceHash) {
            throw IOException("Cadangan berubah atau tidak terpisah dari sumber. Buat cadangan baru.")
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
        } catch (failure: Exception) { throw CommittedOfficeSessionException(failure) }
    }

    fun discard(session: OfficeSession) { session.directory.deleteRecursively() }

    private fun writeAndVerify(context: Context, source: File, destination: Uri, expectedHash: String) {
        // Fungsi verifikasi byte yang sama digunakan oleh editor PDF dan Office.
        VerifiedPdfOutput.copyAndVerify(source, expectedHash,
            { context.contentResolver.openInputStream(destination) },
            { context.contentResolver.openOutputStream(destination, "wt") })
    }

    private fun replaceLocal(target: File, source: File) {
        require(target.canonicalFile != source.canonicalFile)
        val hash = VerifiedPdfOutput.hashFile(source)
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        var finished = false
        try {
            stream = atomic.startWrite()
            source.inputStream().use { it.copyTo(stream) }
            atomic.finishWrite(stream)
            finished = true
            atomic.openRead().use { }
            if (VerifiedPdfOutput.hashFile(target) != hash) throw IOException("File kerja Office tidak tersimpan dengan benar")
        } catch (failure: Exception) {
            if (!finished && stream != null) atomic.failWrite(stream)
            throw failure
        }
    }

    private fun writeState(session: OfficeSession) {
        val json = JSONObject().apply {
            put("sourceUri", session.sourceUri.toString())
            put("title", session.title)
            put("format", session.format.name)
            put("sourceHash", session.sourceHash)
            put("changed", session.changed)
            put("unsaved", session.unsaved)
            put("sourceUncertain", session.sourceUncertain)
        }.toString()
        val atomic = AtomicFile(File(session.directory, "state.json"))
        var stream: FileOutputStream? = null
        var finished = false
        try {
            stream = atomic.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            finished = true
            if (atomic.openRead().bufferedReader().use { it.readText() } != json) throw IOException("Status sesi Office tidak tersimpan")
        } catch (failure: Exception) {
            if (!finished && stream != null) atomic.failWrite(stream)
            throw failure
        }
    }

    private fun sameDocument(context: Context, first: Uri, second: Uri): Boolean {
        if (first == second) return true
        val a = runCatching { context.contentResolver.canonicalize(first) }.getOrNull()
        val b = runCatching { context.contentResolver.canonicalize(second) }.getOrNull()
        if (a != null && a == b) return true
        if (first.authority != second.authority) return false
        return try {
            DocumentsContract.isDocumentUri(context, first) && DocumentsContract.isDocumentUri(context, second) &&
                DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
        } catch (_: Exception) { false }
    }

    private fun displayTitle(context: Context, uri: Uri, format: OfficeFormat): String {
        val name = if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() else uri.lastPathSegment
        val suffix = ".${format.extension}"
        return name.orEmpty().let { if (it.endsWith(suffix, true)) it.dropLast(suffix.length) else it }
            .trim().take(72).ifBlank { "Dokumen ${format.displayName}" }
    }
}
