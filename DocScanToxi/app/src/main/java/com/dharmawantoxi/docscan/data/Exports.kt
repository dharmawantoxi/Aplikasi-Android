package com.dharmawantoxi.docscan.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

object Exports {
    /** Setiap halaman dipasang secara proporsional pada kertas A4 putih. */
    fun writePdf(context: Context, doc: Document, output: OutputStream) {
        require(doc.pages.isNotEmpty()) { "Dokumen tidak memiliki halaman" }
        val pdf = PdfDocument()
        try {
            doc.pages.forEachIndexed { index, name ->
                val file = DocumentStore.pageFile(context, doc.id, name)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IOException("Halaman ${index + 1} tidak dapat dibaca")
                try {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val rect = PdfGeometry.fit(bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, null, RectF(rect.left, rect.top, rect.right, rect.bottom),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    fun savePdf(context: Context, doc: Document, uri: Uri) {
        // Bangun dulu di cache supaya lokasi yang dipilih tidak ditimpa jika pembuatan PDF gagal.
        val temporary = File.createTempFile("export-", ".pdf", context.cacheDir)
        try {
            temporary.outputStream().use { writePdf(context, doc, it) }
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Lokasi penyimpanan tidak dapat dibuka")
            temporary.inputStream().use { input -> output.use { input.copyTo(it) } }
        } finally {
            temporary.delete()
        }
    }

    fun saveJpeg(context: Context, doc: Document, page: String, uri: Uri) {
        require(page in doc.pages)
        val input = DocumentStore.pageFile(context, doc.id, page).inputStream()
        input.use {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Lokasi penyimpanan tidak dapat dibuka")
            output.use { stream -> it.copyTo(stream) }
        }
    }

    private fun sharedFile(context: Context, extension: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 2 * 24 * 60 * 60 * 1000L }
            .forEach { it.delete() }
        return File(dir, "${UUID.randomUUID()}.$extension")
    }

    fun preparePdf(context: Context, doc: Document): Uri {
        val file = sharedFile(context, "pdf")
        try {
            file.outputStream().use { writePdf(context, doc, it) }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun prepareJpeg(context: Context, doc: Document, page: String): Uri {
        require(page in doc.pages)
        val file = sharedFile(context, "jpg")
        try {
            DocumentStore.pageFile(context, doc.id, page).copyTo(file, overwrite = true)
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun share(context: Context, uri: Uri, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Bagikan melalui"))
    }
}
