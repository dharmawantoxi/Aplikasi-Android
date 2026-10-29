package com.dharmawantoxi.docscan

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dharmawantoxi.docscan.data.DocumentNames
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.data.Exports
import com.dharmawantoxi.docscan.data.PdfPageFormat
import com.dharmawantoxi.docscan.edit.ImageEditor
import com.dharmawantoxi.docscan.edit.PageEditActivity
import com.dharmawantoxi.docscan.edit.TextEditActivity
import com.dharmawantoxi.docscan.edit.TextOverlayRenderer
import com.dharmawantoxi.docscan.edit.TextOverlaySpec
import com.dharmawantoxi.docscan.edit.TextOverlayView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class PdfEditFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun makePdf(sizes: List<Pair<Int, Int>>, colors: List<Int> = List(sizes.size) { Color.WHITE }): File {
        val file = File.createTempFile("source-pdf-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            sizes.forEachIndexed { i, (width, height) ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, i + 1).create())
                page.canvas.drawColor(colors[i])
                page.canvas.drawText("Halaman ${i + 1}", 30f, 60f, Paint().apply { color = Color.BLACK; textSize = 20f })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    private fun makePhoto(): File {
        val file = File.createTempFile("pdf-added-photo-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun assertPage(pdf: PdfRenderer, index: Int, width: Int, height: Int, expectedColor: Int? = null) {
        pdf.openPage(index).use { page ->
            assertEquals(width, page.width)
            assertEquals(height, page.height)
            if (expectedColor != null) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    // Dekat tepi, agar margin A4 yang tidak diinginkan ikut terdeteksi.
                    val actual = bitmap.getPixel(4, 4)
                    assertTrue("Warna halaman berubah di tepi: $actual",
                        kotlin.math.abs(Color.red(expectedColor) - Color.red(actual)) < 45 &&
                            kotlin.math.abs(Color.green(expectedColor) - Color.green(actual)) < 45 &&
                            kotlin.math.abs(Color.blue(expectedColor) - Color.blue(actual)) < 45)
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun importPdfEditReorderAndExportKeepPageSizesAndSource() {
        val red = Color.rgb(210, 30, 30)
        val blue = Color.rgb(30, 80, 210)
        val temporarySource = makePdf(listOf(300 to 500, 640 to 320), listOf(red, blue))
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val source = File(sharedDir, "Nama PDF sangat panjang ".repeat(4) + System.nanoTime() + ".pdf")
        temporarySource.copyTo(source, overwrite = true)
        temporarySource.delete()
        val original = source.readBytes()
        val output = File.createTempFile("edited-pdf-", ".pdf", context.cacheDir)
        val photo = makePhoto()
        var id: String? = null
        try {
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val imported = DocumentStore.importPdf(context, contentUri)
            id = imported.id
            assertEquals(2, imported.pages.size)
            assertTrue(DocumentNames.fileName(imported.title).endsWith(" (edit)"))
            val portrait = imported.pages[0]
            val landscape = imported.pages[1]
            assertEquals(PdfPageFormat(300, 500), imported.pageFormats[portrait])
            assertEquals(PdfPageFormat(640, 320), imported.pageFormats[landscape])
            assertEquals(imported.pageFormats, DocumentStore.get(context, imported.id)?.pageFormats)
            assertArrayEquals(original, source.readBytes())

            Exports.savePdf(context, imported, Uri.fromFile(output))
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(2, pdf.pageCount)
                assertPage(pdf, 0, 300, 500, red)
                assertPage(pdf, 1, 640, 320, blue)
            }

            val image = BitmapFactory.decodeFile(DocumentStore.pageFile(context, imported.id, portrait).absolutePath)
            val spec = TextOverlaySpec("Catatan baru", 0.2f, 0.6f, 0.08f, Color.WHITE)
            val layout = TextOverlayRenderer.layout(image.width, image.height, spec)
            assertTrue(layout.bounds.left >= 0f && layout.bounds.right <= image.width)
            assertTrue(layout.bounds.top >= 0f && layout.bounds.bottom <= image.height)
            val annotated = TextOverlayRenderer.render(image, spec)
            try {
                assertFalse("Teks harus mengubah gambar", image.sameAs(annotated))
                val updated = DocumentStore.replacePage(context, imported.id, portrait, annotated)
                assertEquals(PdfPageFormat(300, 500), updated.pageFormats[portrait])
            } finally { annotated.recycle(); image.recycle() }

            val landscapeImage = BitmapFactory.decodeFile(DocumentStore.pageFile(context, imported.id, landscape).absolutePath)
            val rotated = ImageEditor.rotate(landscapeImage)
            try {
                val updated = DocumentStore.replacePage(context, imported.id, landscape, rotated)
                assertEquals(PdfPageFormat(320, 640), updated.pageFormats[landscape])
            } finally { rotated.recycle(); landscapeImage.recycle() }

            val portraitImage = BitmapFactory.decodeFile(DocumentStore.pageFile(context, imported.id, portrait).absolutePath)
            val cropped = Bitmap.createBitmap(portraitImage, 0, 0, portraitImage.width / 2, portraitImage.height)
            try {
                val updated = DocumentStore.replacePage(context, imported.id, portrait, cropped)
                assertEquals(PdfPageFormat(150, 500), updated.pageFormats[portrait])
            } finally { cropped.recycle(); portraitImage.recycle() }

            val added = DocumentStore.addPages(context, imported.id, listOf(Uri.fromFile(photo)))
            assertNull(added.pageFormats[added.pages.last()]) // Foto tetap A4.
            val moved = DocumentStore.movePage(context, imported.id, landscape, -1)
            assertEquals(landscape, moved.pages.first())
            Exports.savePdf(context, moved, Uri.fromFile(output))
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(3, pdf.pageCount)
                assertPage(pdf, 0, 320, 640, blue)
                assertPage(pdf, 1, 150, 500, red)
                assertPage(pdf, 2, 595, 842)
            }
            val remaining = DocumentStore.deletePage(context, imported.id, portrait)
            assertFalse(remaining.pageFormats.containsKey(portrait))
            assertEquals(remaining.pageFormats, DocumentStore.get(context, imported.id)?.pageFormats)
            assertFalse(DocumentStore.pageFile(context, imported.id, portrait).exists())
            assertArrayEquals("PDF sumber tidak boleh berubah", original, source.readBytes())
        } finally {
            id?.let { DocumentStore.delete(context, it) }
            source.delete(); output.delete(); photo.delete()
        }
    }

    @Test fun rejectInvalidEncryptedAndOversizePdfWithoutPartialDocuments() {
        val invalid = File.createTempFile("invalid-pdf-", ".pdf", context.cacheDir).apply { writeText("bukan pdf") }
        val encrypted = File.createTempFile("encrypted-pdf-", ".pdf", context.cacheDir)
        instrumentation.context.assets.open("password-protected.pdf").use { input -> encrypted.outputStream().use { input.copyTo(it) } }
        val tooMany = makePdf(List(DocumentStore.MAX_PAGES + 1) { 80 to 80 })
        val tooBig = File.createTempFile("too-big-pdf-", ".pdf", context.cacheDir)
        RandomAccessFile(tooBig, "rw").use { it.setLength(DocumentStore.MAX_PDF_BYTES + 1) }
        val documentsDir = File(context.filesDir, "documents")
        fun folderNames() = documentsDir.listFiles().orEmpty().map { it.name }.toSet()
        fun temporaryNames() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("import-pdf-") }.map { it.name }.toSet()
        val foldersBefore = folderNames()
        val temporaryBefore = temporaryNames()
        try {
            listOf(invalid, encrypted, tooMany, tooBig).forEachIndexed { index, file ->
                val failure = runCatching { DocumentStore.importPdf(context, Uri.fromFile(file)) }.exceptionOrNull()
                assertNotNull("PDF tidak semestinya diterima: ${file.name}", failure)
                if (index == 2) assertTrue(failure!!.message.orEmpty().contains("maksimal 25"))
                if (index == 3) assertTrue(failure!!.message.orEmpty().contains("100 MB"))
                assertEquals("Dokumen parsial tertinggal", foldersBefore, folderNames())
                assertEquals("Berkas sementara tertinggal", temporaryBefore, temporaryNames())
            }
        } finally { invalid.delete(); encrypted.delete(); tooMany.delete(); tooBig.delete() }
    }

    @Test fun homeWarningAndPdfPickerOpenNewDocument() {
        val temporarySource = makePdf(listOf(240 to 360))
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val source = File(sharedDir, "pilihan-${System.nanoTime()}.pdf")
        temporarySource.copyTo(source, overwrite = true)
        temporarySource.delete()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val original = source.readBytes()
        val before = DocumentStore.list(context).map { it.id }.toSet()
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val picker = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
        val documentMonitor = instrumentation.addMonitor(DocumentActivity::class.java.name, null, false)
        var opened: Activity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { home ->
                home.onActivity { activity ->
                    val edit = findDescription(activity.window.decorView, "Edit PDF yang sudah ada")
                    assertNotNull("Tombol Edit PDF tidak ditemukan", edit)
                    assertTrue(edit!!.performClick())
                }
                assertNotNull("Peringatan batasan PDF harus muncul", waitForNode("teks asli tidak dapat diedit atau dicari"))
                assertNotNull("Peringatan tanda tangan harus muncul", waitForNode("tanda tangan digital"))
                clickNode("Pilih PDF")
                opened = instrumentation.waitForMonitorWithTimeout(documentMonitor, 30_000)
                assertNotNull("Dokumen hasil impor tidak terbuka", opened)
                assertTrue("Pemilih PDF tidak dipanggil", picker.hits > 0)
                val id = opened!!.intent.getStringExtra(DocumentActivity.EXTRA_ID)
                assertNotNull(DocumentStore.get(context, id!!))
                assertArrayEquals(original, source.readBytes())
            }
        } finally {
            opened?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.removeMonitor(picker)
            instrumentation.removeMonitor(documentMonitor)
            DocumentStore.list(context).filter { it.id !in before }.forEach { DocumentStore.delete(context, it.id) }
            source.delete()
        }
    }

    @Test fun addTextScreenPlacesAndSavesVisibleText() {
        val source = makePdf(listOf(300 to 500))
        val doc = DocumentStore.importPdf(context, Uri.fromFile(source))
        val page = doc.pages.first()
        val pageFile = DocumentStore.pageFile(context, doc.id, page)
        val before = pageFile.readBytes()
        try {
            val intent = Intent(context, TextEditActivity::class.java)
                .putExtra(DocumentActivity.EXTRA_ID, doc.id)
                .putExtra(PageEditActivity.EXTRA_PAGE, page)
            ActivityScenario.launch<TextEditActivity>(intent).use { editor ->
                var loaded = false
                repeat(100) {
                    if (!loaded) {
                        editor.onActivity { activity -> loaded = findType<TextOverlayView>(activity.window.decorView)?.bitmap != null }
                        if (!loaded) Thread.sleep(100)
                    }
                }
                assertTrue("Pratinjau tidak dimuat", loaded)
                instrumentation.waitForIdleSync()
                editor.onActivity { activity ->
                    val root = activity.window.decorView
                    findType<EditText>(root)!!.setText("Catatan uji")
                    findType<SeekBar>(root)!!.progress = 80
                    assertTrue(findDescription(root, "Warna Merah")!!.performClick())
                    val view = findType<TextOverlayView>(root)!!
                    val now = android.os.SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, view.width / 2f, view.height * 0.7f, 0)
                    val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, view.width / 2f, view.height * 0.7f, 0)
                    try { assertTrue(view.dispatchTouchEvent(down)); view.dispatchTouchEvent(up) }
                    finally { down.recycle(); up.recycle() }
                    assertTrue(view.spec.y > 0.5f)
                    assertTrue(findText(root, "Posisi")!!.performClick())
                }
                tapNode("Kiri atas")
                editor.onActivity { activity ->
                    val root = activity.window.decorView
                    val view = findType<TextOverlayView>(root)!!
                    assertEquals(0f, view.spec.x, 0.001f)
                    assertEquals(0f, view.spec.y, 0.001f)
                    assertEquals(0xFFBA1A1A.toInt(), view.spec.color)
                    val save = findDescription(root, "Simpan teks pada halaman")!!
                    assertTrue(save.isEnabled)
                    assertTrue(save.performClick())
                }
                var changed = false
                repeat(100) {
                    if (!changed) {
                        Thread.sleep(100)
                        val current = DocumentStore.get(context, doc.id)
                        changed = current != null && current.updatedAt != doc.updatedAt &&
                            !pageFile.readBytes().contentEquals(before)
                    }
                }
                assertTrue("Teks tidak tersimpan", changed)
            }
            val updated = DocumentStore.get(context, doc.id)!!
            assertEquals(PdfPageFormat(300, 500), updated.pageFormats[page])
            val image = BitmapFactory.decodeFile(pageFile.absolutePath)
            try {
                var redPixels = 0
                for (y in 0 until image.height / 4 step 2) {
                    for (x in 0 until image.width * 3 / 4 step 2) {
                        val p = image.getPixel(x, y)
                        if (Color.red(p) > 120 && Color.green(p) < 110 && Color.blue(p) < 110) redPixels++
                    }
                }
                assertTrue("Teks merah tidak terlihat di hasil", redPixels > 10)
            } finally { image.recycle() }
        } finally { DocumentStore.delete(context, doc.id); source.delete() }
    }

    private fun waitForNode(text: String): AccessibilityNodeInfo? {
        repeat(100) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val found = root?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            if (found != null) return found
            Thread.sleep(100)
        }
        return null
    }

    private fun clickNode(text: String) {
        var node = waitForNode(text)
        assertNotNull("Elemen dialog tidak ditemukan: $text", node)
        var clicked = false
        // Item daftar AlertDialog sering menempatkan teks pada anak yang tidak dapat diklik.
        while (node != null && !clicked) {
            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) node = node.parent
        }
        assertTrue("Elemen dialog tidak dapat ditekan: $text", clicked)
        instrumentation.waitForIdleSync()
    }

    private fun tapNode(text: String) {
        val node = waitForNode(text)
        assertNotNull("Elemen dialog tidak ditemukan: $text", node)
        val bounds = Rect()
        node!!.getBoundsInScreen(bounds)
        assertTrue("Elemen dialog tidak terlihat: $text", !bounds.isEmpty)
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally { down.recycle(); up.recycle() }
        instrumentation.waitForIdleSync()
    }

    private fun findDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findDescription(view.getChildAt(i), description)?.let { return it }
        }
        return null
    }

    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findText(view.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private inline fun <reified T : View> findType(view: View): T? = findType(view, T::class.java)

    private fun <T : View> findType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findType(view.getChildAt(i), type)?.let { return it }
        }
        return null
    }
}
