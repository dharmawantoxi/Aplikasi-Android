package com.dharmawantoxi.docscan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.data.Exports
import com.dharmawantoxi.docscan.edit.ImageEditor
import com.dharmawantoxi.docscan.edit.PageEditActivity
import com.dharmawantoxi.docscan.ocr.OcrActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentFlowTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun makePhoto(rotateExif: Boolean = false): File {
        val file = File.createTempFile("test-photo-", ".jpg", context.cacheDir)
        val image = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        Canvas(image).drawRect(16f, 32f, 48f, 96f, Paint().apply { color = Color.BLACK })
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        image.recycle()
        if (rotateExif) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
        }
        return file
    }

    @Test fun importEditReorderAndExportPdf() {
        val first = makePhoto(rotateExif = true)
        val second = makePhoto()
        val pdf = File.createTempFile("test-output-", ".pdf", context.cacheDir)
        val jpg = File.createTempFile("test-output-", ".jpg", context.cacheDir)
        var id: String? = null
        try {
            val created = DocumentStore.create(context, listOf(Uri.fromFile(first)))
            id = created.id
            assertEquals(1, created.pages.size)
            assertNotNull(DocumentStore.get(context, created.id))

            // Orientasi EXIF diperbaiki ketika halaman diimpor.
            val image = BitmapFactory.decodeFile(DocumentStore.pageFile(context, created.id, created.pages[0]).absolutePath)
            assertEquals(128, image.width)
            assertEquals(64, image.height)
            val cropped = ImageEditor.crop(image, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 1)
            DocumentStore.replacePage(context, created.id, created.pages[0], cropped)
            cropped.recycle()
            image.recycle()

            val added = DocumentStore.addPages(context, created.id, listOf(Uri.fromFile(second)))
            assertEquals(2, added.pages.size)
            val moved = DocumentStore.movePage(context, created.id, added.pages[1], -1)
            assertEquals(added.pages[1], moved.pages.first())
            val renamed = DocumentStore.rename(context, created.id, "Uji dokumen")
            assertEquals("Uji dokumen", renamed.title)

            Exports.savePdf(context, renamed, Uri.fromFile(pdf))
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // Tanda hitam di tengah foto harus benar-benar muncul pada PDF.
                        assertTrue(Color.red(rendered.getPixel(page.width / 2, page.height / 2)) < 100)
                    } finally { rendered.recycle() }
                }
            }
            Exports.saveJpeg(context, renamed, renamed.pages.first(), Uri.fromFile(jpg))
            assertNotNull(BitmapFactory.decodeFile(jpg.absolutePath))
            val sharedUri = Exports.preparePdf(context, renamed)
            assertEquals("content", sharedUri.scheme)
            val firstByte = context.contentResolver.openInputStream(sharedUri)?.use { it.read() } ?: -1
            assertEquals('%'.code, firstByte)

            val oneLeft = DocumentStore.deletePage(context, created.id, renamed.pages.last())
            assertEquals(1, oneLeft.pages.size)
        } finally {
            id?.let { DocumentStore.delete(context, it) }
            first.delete(); second.delete(); pdf.delete(); jpg.delete()
        }
    }

    @Test fun homeAndDocumentScreensOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(containsText(activity.window.decorView, "DocScan Toxi"))
                assertTrue(containsText(activity.window.decorView, "Mulai pindai"))
                assertTrue(containsText(activity.window.decorView, "Edit PDF"))
            }
        }

        val photo = makePhoto()
        val doc = DocumentStore.create(context, listOf(Uri.fromFile(photo)))
        try {
            val intent = Intent(context, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_ID, doc.id)
            ActivityScenario.launch<DocumentActivity>(intent).use { scenario ->
                var loaded = false
                var attempts = 0
                while (!loaded && attempts++ < 50) {
                    scenario.onActivity { loaded = containsText(it.window.decorView, "Simpan PDF") }
                    if (!loaded) Thread.sleep(100)
                }
                assertTrue("Layar dokumen tidak selesai dimuat", loaded)
                scenario.onActivity { activity ->
                    assertTrue(containsText(activity.window.decorView, "Tambah"))
                    assertTrue(containsText(activity.window.decorView, "Pindai teks"))
                    assertTrue(containsText(activity.window.decorView, "Tambah teks"))
                }
            }
            val editIntent = Intent(context, PageEditActivity::class.java)
                .putExtra(DocumentActivity.EXTRA_ID, doc.id)
                .putExtra(PageEditActivity.EXTRA_PAGE, doc.pages.first())
            ActivityScenario.launch<PageEditActivity>(editIntent).use { scenario ->
                scenario.onActivity { assertTrue(containsText(it.window.decorView, "Edit halaman")) }
            }
            val ocrIntent = Intent(context, OcrActivity::class.java).putExtra(DocumentActivity.EXTRA_ID, doc.id)
            ActivityScenario.launch<OcrActivity>(ocrIntent).use { scenario ->
                var done = false
                var attempts = 0
                while (!done && attempts++ < 120) {
                    scenario.onActivity { activity ->
                        done = containsTextPrefix(activity.window.decorView, "Tidak ada teks yang dikenali.") ||
                            containsTextPrefix(activity.window.decorView, "Selesai. Anda dapat memperbaiki teks")
                    }
                    if (!done) Thread.sleep(500)
                }
                assertTrue("OCR tidak selesai pada emulator", done)
            }
        } finally {
            DocumentStore.delete(context, doc.id)
            photo.delete()
        }
    }

    private fun containsText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) if (containsText(view.getChildAt(i), text)) return true
        }
        return false
    }

    private fun containsTextPrefix(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString().startsWith(text)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) if (containsTextPrefix(view.getChildAt(i), text)) return true
        }
        return false
    }
}
