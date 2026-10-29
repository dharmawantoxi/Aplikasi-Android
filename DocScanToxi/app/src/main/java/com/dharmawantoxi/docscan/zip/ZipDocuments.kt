package com.dharmawantoxi.docscan.zip

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.dharmawantoxi.docscan.pdf.VerifiedPdfOutput
import com.dharmawantoxi.docscan.ui.Language
import java.io.File
import java.io.IOException
import java.util.UUID

/** SAF providers may not offer atomic writes; never deliberately reuse an existing document ID. */
object ZipDocuments {
    private val folderMime = DocumentsContract.Document.MIME_TYPE_DIR

    fun extractToTree(context: Context, source: Uri, tree: Uri) {
        val dir = File(context.cacheDir, "zip-extract-${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
        try {
            val archive = File(dir, "source.zip")
            copyLimited(context, source, archive, ZipArchive.MAX_ARCHIVE_BYTES)
            val staged = File(dir, "staged")
            if (!staged.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
            val entries = ZipArchive.extract(archive, staged)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val reserved = mutableSetOf(DocumentsContract.getDocumentId(root))
            if (source.authority == tree.authority) {
                runCatching { DocumentsContract.getDocumentId(source) }.getOrNull()?.let(reserved::add)
            }
            // A unique subfolder avoids deliberately overwriting files in the chosen tree.
            val outputRoot = createFresh(context, tree, root, folderMime, "${Language.text(context, "Hasil ZIP", "Extracted ZIP")} ${UUID.randomUUID()}", reserved)
            val folders = mutableMapOf("" to outputRoot)
            val allDirs = mutableSetOf<String>()
            entries.forEach { entry ->
                val parts = entry.path.split('/')
                val depth = if (entry.directory) parts.size else parts.size - 1
                for (i in 1..depth) allDirs.add(parts.take(i).joinToString("/"))
            }
            allDirs.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it }).forEach { path ->
                val parent = path.substringBeforeLast('/', "")
                val uri = createFresh(context, tree, folders.getValue(parent), folderMime, path.substringAfterLast('/'), reserved)
                folders[path] = uri
            }
            entries.filterNot { it.directory }.forEach { entry ->
                val parent = entry.path.substringBeforeLast('/', "")
                val name = entry.path.substringAfterLast('/')
                val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                val target = createFresh(context, tree, folders.getValue(parent), mime, name, reserved)
                val stagedFile = File(staged, entry.path)
                val hash = VerifiedPdfOutput.hashFile(stagedFile)
                VerifiedPdfOutput.copyAndVerify(stagedFile, hash,
                    { context.contentResolver.openInputStream(target) },
                    { context.contentResolver.openOutputStream(target, "wt") })
            }
        } finally { dir.deleteRecursively() }
    }

    private fun createFresh(context: Context, tree: Uri, parent: Uri, mime: String, name: String, reserved: MutableSet<String>): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val ids = mutableSetOf<String>()
        val resolver = context.contentResolver
        val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw IOException("Isi folder tujuan tidak dapat diperiksa")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: throw IOException("ID file tujuan tidak tersedia")
                val existingName = it.getString(1) ?: throw IOException("Nama file tujuan tidak tersedia")
                if (existingName.equals(name, ignoreCase = true)) throw IOException("Nama tujuan sudah ada: $name")
                ids.add(id)
            }
        }
        val created = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("Tidak dapat membuat $name")
        val id = DocumentsContract.getDocumentId(created)
        if (id in ids || !reserved.add(id)) throw IOException("Penyedia mengembalikan file yang sudah ada")
        val actual = resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (actual != name) throw IOException("Penyedia mengubah nama $name; ekstraksi dihentikan")
        return created
    }

    fun copyLimited(context: Context, uri: Uri, file: File, limit: Long): Long {
        var size = 0L
        (context.contentResolver.openInputStream(uri) ?: throw IOException("File sumber tidak dapat dibuka")).use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    size += n
                    if (size > limit) throw IOException("Ukuran file melebihi batas")
                    output.write(buffer, 0, n)
                }
            }
        }
        return size
    }
}
