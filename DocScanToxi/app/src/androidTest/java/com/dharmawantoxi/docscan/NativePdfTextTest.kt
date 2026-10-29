package com.dharmawantoxi.docscan

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dharmawantoxi.docscan.pdf.NativePdfActivity
import com.dharmawantoxi.docscan.pdf.NativePdfFiles
import com.dharmawantoxi.docscan.pdf.NativePdfText
import com.dharmawantoxi.docscan.pdf.PdfTextSegment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativePdfTextTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before fun setup() { PDFBoxResourceLoader.init(context) }

    private fun tempPdf(name: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        return File(dir, "$name-${UUID.randomUUID()}.pdf")
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun samplePdf(): File {
        val file = tempPdf("native-source")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(300f, 500f))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { canvas ->
                canvas.beginText()
                canvas.setFont(PDType1Font.HELVETICA, 15f)
                canvas.newLineAtOffset(50f, 400f)
                canvas.showText("Nama Lama")
                canvas.endText()
                canvas.beginText()
                canvas.setFont(PDType1Font.HELVETICA_BOLD, 11f)
                canvas.newLineAtOffset(50f, 350f)
                canvas.showText("Teks lain")
                canvas.endText()
            }
            page.annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(50f, 300f, 100f, 15f)
                action = PDActionURI().apply { uri = "https://example.com" }
            })
            pdf.addPage(PDPage(PDRectangle(640f, 320f)))
            pdf.save(file)
        }
        return file
    }

    @Test fun replacesRealTextWithoutFlatteningAndPreservesOtherObjects() {
        val source = samplePdf()
        val before = source.readBytes()
        val output = tempPdf("native-result")
        val second = tempPdf("native-result-again")
        try {
            assertEquals(2, NativePdfText.inspect(source).pageCount)
            val page = NativePdfText.readPage(source, 0)
            val segment = page.segments.single { it.text == "Nama Lama" }
            NativePdfText.replace(source, output, 0, segment, "Nama Baru")
            assertArrayEquals("Sumber tidak boleh berubah saat mengedit", before, source.readBytes())
            PDDocument.load(output).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("Nama Baru"))
                assertFalse(text.contains("Nama Lama"))
                assertTrue(text.contains("Teks lain"))
                assertEquals(2, pdf.numberOfPages)
                assertEquals(300f, pdf.getPage(0).mediaBox.width, 0f)
                assertEquals(500f, pdf.getPage(0).mediaBox.height, 0f)
                assertEquals(640f, pdf.getPage(1).mediaBox.width, 0f)
                assertTrue(pdf.getPage(0).resources.fontNames.iterator().hasNext())
                val annotation = pdf.getPage(0).annotations.single() as PDAnnotationLink
                assertEquals("https://example.com", (annotation.action as PDActionURI).uri)
                // Ini bukan sekadar kotak putih di atas string lama.
                val contents = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                assertFalse(contents.contains("Nama Lama"))
            }
            val updated = NativePdfText.readPage(output, 0).segments.single { it.text == "Nama Baru" }
            NativePdfText.replace(output, second, 0, updated, "Nama Akhir")
            PDDocument.load(second).use { assertTrue(PDFTextStripper().getText(it).contains("Nama Akhir")) }
        } finally { source.delete(); output.delete(); second.delete() }
    }

    @Test fun handlesTextArraysAndSkipsInvisibleOrActualText() {
        val source = tempPdf("native-array")
        val output = tempPdf("native-array-result")
        try {
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(300f, 500f))
                pdf.addPage(page)
                page.resources = PDResources().apply {
                    put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
                    put(COSName.getPDFName("F2"), PDType1Font.COURIER)
                    put(COSName.getPDFName("Invisible"), PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0f })
                }
                val stream = PDStream(pdf)
                stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
                    out.write("""
                        q BT /F1 12 Tf 40 400 Td [(Hello) -40 ( World)] TJ ET Q
                        /Span << /ActualText (Alt) >> BDC BT /F1 12 Tf 40 350 Td (Tagged Text) Tj ET EMC
                        BT /F1 12 Tf 3 Tr 40 300 Td (Hidden OCR) Tj ET
                        /OC /Layer1 BDC BT /F1 12 Tf 0 Tr 40 270 Td (Optional Layer) Tj ET EMC
                        q /Invisible gs BT /F1 12 Tf 0 Tr 40 260 Td (Transparent) Tj ET Q
                        q BT /F2 12 Tf 0 Tr 40 250 Td (Nested) Tj ET Q
                    """.trimIndent().toByteArray(Charsets.ISO_8859_1))
                }
                page.setContents(stream)
                pdf.save(source)
            }
            val page = NativePdfText.readPage(source, 0)
            assertEquals(listOf("Hello World", "Nested"), page.segments.map { it.text })
            assertEquals(4, page.skipped)
            NativePdfText.replace(source, output, 0, page.segments.first(), "Goodbye")
            PDDocument.load(output).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("Goodbye"))
                assertFalse(text.contains("Hello"))
                assertTrue(text.contains("Tagged Text"))
            }
        } finally { source.delete(); output.delete() }
    }

    @Test fun quoteOperatorsAndInlineImagesSurviveReplacement() {
        val source = tempPdf("native-quotes")
        val first = tempPdf("native-quote-one")
        val second = tempPdf("native-quote-two")
        try {
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(300f, 500f))
                pdf.addPage(page)
                page.resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                val stream = PDStream(pdf)
                stream.createOutputStream().use { out -> out.write("""
                    q 10 0 0 10 0 0 cm BI /W 1 /H 1 /CS /RGB /BPC 8 /F /AHx ID
                    FF0000>
                    EI Q
                    BT /F1 12 Tf 14 TL 50 400 Td (First) Tj (Second) ' 1 2 (Third) " ET
                """.trimIndent().toByteArray(Charsets.ISO_8859_1)) }
                page.setContents(stream)
                pdf.save(source)
            }
            val originalImage = inlineImageBytes(source)
            val initial = NativePdfText.readPage(source, 0).segments
            assertEquals(listOf("First", "Second", "Third"), initial.map { it.text })
            NativePdfText.replace(source, first, 0, initial[1], "Kedua")
            assertArrayEquals(originalImage, inlineImageBytes(first))
            val afterFirst = NativePdfText.readPage(first, 0).segments
            NativePdfText.replace(first, second, 0, afterFirst.single { it.text == "Third" }, "Ketiga")
            assertEquals(listOf("First", "Kedua", "Ketiga"), NativePdfText.readPage(second, 0).segments.map { it.text })
            assertArrayEquals(originalImage, inlineImageBytes(second))
        } finally { source.delete(); first.delete(); second.delete() }
    }

    private fun inlineImageBytes(file: File): ByteArray = PDDocument.load(file).use { pdf ->
        val parser = PDFStreamParser(pdf.getPage(0))
        parser.parse()
        // Pemisah whitespace setelah ASCIIHex dapat berubah ketika aliran ditulis ulang.
        (parser.tokens.single { it is Operator && it.name == "BI" } as Operator).imageData
            .toString(Charsets.US_ASCII).trim().toByteArray(Charsets.US_ASCII)
    }

    @Test fun embeddedFontsCanBeEditedButSubsetRejectsMissingGlyphs() {
        for (subset in listOf(false, true)) {
            val source = tempPdf("embedded-$subset")
            val output = tempPdf("embedded-result-$subset")
            try {
                PDDocument().use { pdf ->
                    val page = PDPage()
                    pdf.addPage(page)
                    val font = PDType0Font.load(pdf,
                        PDFBoxResourceLoader.getStream("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"), subset)
                    PDPageContentStream(pdf, page).use { canvas ->
                        canvas.beginText()
                        canvas.setFont(font, 16f)
                        canvas.newLineAtOffset(40f, 700f)
                        canvas.showText("Hello World")
                        canvas.endText()
                    }
                    pdf.save(source)
                }
                val segment = NativePdfText.readPage(source, 0).segments.single()
                NativePdfText.replace(source, output, 0, segment, "World Hello")
                assertEquals("World Hello", NativePdfText.readPage(output, 0).segments.single().text)
                if (subset) assertNotNull(runCatching {
                    NativePdfText.replace(source, output, 0, segment, "Hello there")
                }.exceptionOrNull()) else {
                    NativePdfText.replace(source, output, 0, segment, "Hello there")
                    assertEquals("Hello there", NativePdfText.readPage(output, 0).segments.single().text)
                }
            } finally { source.delete(); output.delete() }
        }
    }

    @Test fun rejectsUnsupportedFontCharactersStaleSegmentsAndSignedPdfs() {
        val source = samplePdf()
        val before = source.readBytes()
        val output = tempPdf("native-rejected")
        val signed = tempPdf("native-signed")
        val encrypted = tempPdf("native-encrypted")
        try {
            val segment = NativePdfText.readPage(source, 0).segments.single { it.text == "Nama Lama" }
            assertNotNull(runCatching { NativePdfText.replace(source, output, 0, segment, "Emoji 🚀") }.exceptionOrNull())
            assertNotNull(runCatching { NativePdfText.replace(source, output, 0, segment.copy(text = "Stale"), "Baru") }.exceptionOrNull())
            assertNotNull(runCatching { NativePdfText.replace(source, output, 0, segment, "Dua\nbaris") }.exceptionOrNull())
            assertArrayEquals(before, source.readBytes())
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                val permissions = COSDictionary().apply { setItem(COSName.getPDFName("DocMDP"), COSDictionary()) }
                pdf.documentCatalog.cosObject.setItem(COSName.getPDFName("Perms"), permissions)
                pdf.save(signed)
            }
            assertNotNull(runCatching { NativePdfText.inspect(signed) }.exceptionOrNull())
            instrumentation.context.assets.open("password-protected.pdf").use { input -> encrypted.outputStream().use { input.copyTo(it) } }
            assertNotNull(runCatching { NativePdfText.inspect(encrypted) }.exceptionOrNull())
        } finally { source.delete(); output.delete(); signed.delete(); encrypted.delete() }
    }

    @Test fun overwriteRequiresBackupAndKeepsSourceUntilConfirmed() {
        val source = samplePdf()
        val backup = tempPdf("native-backup")
        val copy = tempPdf("native-copy")
        val original = source.readBytes()
        val session = NativePdfFiles.open(context, uri(source))
        try {
            val segment = NativePdfText.readPage(session.working, 0).segments.single { it.text == "Nama Lama" }
            NativePdfFiles.replaceText(session, 0, segment, "Nama Baru")
            assertArrayEquals(original, source.readBytes())
            assertTrue(session.changed)
            assertTrue(session.unsaved)
            assertNotNull(runCatching { NativePdfFiles.overwriteOriginal(context, session) }.exceptionOrNull())
            assertNotNull(runCatching { NativePdfFiles.saveCopy(context, session, uri(source)) }.exceptionOrNull())
            assertArrayEquals(original, source.readBytes())
            NativePdfFiles.saveCopy(context, session, uri(copy))
            assertFalse(session.unsaved)
            PDDocument.load(copy).use { assertTrue(PDFTextStripper().getText(it).contains("Nama Baru")) }
            NativePdfFiles.saveBackup(context, session, uri(backup))
            assertArrayEquals(original, backup.readBytes())
            NativePdfFiles.overwriteOriginal(context, session)
            assertFalse(session.changed)
            assertFalse(session.unsaved)
            PDDocument.load(source).use { assertTrue(PDFTextStripper().getText(it).contains("Nama Baru")) }
            assertArrayEquals(original, backup.readBytes())
            val restored = NativePdfFiles.restore(context, session.id)
            assertNotNull(restored)
            assertFalse(restored!!.changed)
        } finally { NativePdfFiles.discard(session); source.delete(); backup.delete(); copy.delete() }
    }

    @Test fun concurrentSourceChangeStopsOverwriteBeforeAnyWrite() {
        val source = samplePdf()
        val backup = tempPdf("native-backup")
        val session = NativePdfFiles.open(context, uri(source))
        try {
            val segment = NativePdfText.readPage(session.working, 0).segments.single { it.text == "Nama Lama" }
            NativePdfFiles.replaceText(session, 0, segment, "Nama Baru")
            NativePdfFiles.saveBackup(context, session, uri(backup))
            source.appendText("\n% change by another application\n")
            val changed = source.readBytes()
            val error = runCatching { NativePdfFiles.overwriteOriginal(context, session) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("berubah sejak dibuka"))
            assertArrayEquals(changed, source.readBytes())
            assertNotNull(runCatching { NativePdfFiles.overwriteOriginal(context, session) }.exceptionOrNull())
        } finally { NativePdfFiles.discard(session); source.delete(); backup.delete() }
    }

    @Test fun restoringSessionRecoversInterruptedAtomicFile() {
        val source = samplePdf()
        val session = NativePdfFiles.open(context, uri(source))
        try {
            val working = session.working.readBytes()
            val legacyBackup = File(session.working.path + ".bak")
            assertTrue(session.working.renameTo(legacyBackup))
            session.working.writeText("penulisan terhenti")
            val restored = NativePdfFiles.restore(context, session.id)
            assertNotNull(restored)
            assertArrayEquals(working, restored!!.working.readBytes())
        } finally { NativePdfFiles.discard(session); source.delete() }
    }

    @Test fun restoredSessionFlagsAChangedSourceAsUncertain() {
        val source = samplePdf()
        val session = NativePdfFiles.open(context, uri(source))
        try {
            source.appendText("\n% changed while activity was stopped\n")
            val restored = NativePdfFiles.restore(context, session.id)
            assertNotNull(restored)
            assertTrue(restored!!.sourceUncertain)
            assertTrue(runCatching { NativePdfFiles.overwriteOriginal(context, restored) }
                .exceptionOrNull()?.message.orEmpty().contains("tidak pasti"))
        } finally { NativePdfFiles.discard(session); source.delete() }
    }

    @Test fun changedBackupCannotBeUsedToOverwrite() {
        val source = samplePdf()
        val backup = tempPdf("native-backup")
        val original = source.readBytes()
        val session = NativePdfFiles.open(context, uri(source))
        try {
            val segment = NativePdfText.readPage(session.working, 0).segments.single { it.text == "Nama Lama" }
            NativePdfFiles.replaceText(session, 0, segment, "Nama Baru")
            NativePdfFiles.saveBackup(context, session, uri(backup))
            backup.writeText("cadangan rusak")
            assertNotNull(runCatching { NativePdfFiles.overwriteOriginal(context, session) }.exceptionOrNull())
            assertArrayEquals(original, source.readBytes())
        } finally { NativePdfFiles.discard(session); source.delete(); backup.delete() }
    }

    @Test fun homePickerOpensNativePdfEditor() {
        val source = samplePdf()
        val original = source.readBytes()
        val uri = uri(source)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("application/pdf")
        }
        val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val picker = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        val monitor = instrumentation.addMonitor(NativePdfActivity::class.java.name, null, false)
        var opened: Activity? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { home ->
                home.onActivity { activity ->
                    val edit = findDescription(activity.window.decorView, "Edit PDF yang sudah ada")
                    assertNotNull(edit)
                    assertTrue(edit!!.performClick())
                }
                val choose = waitForAccessible { it.text?.toString() == "Pilih PDF" }
                assertNotNull("Pilihan PDF asli tidak tampil", choose)
                tap(choose!!)
                opened = instrumentation.waitForMonitorWithTimeout(monitor, 30_000)
                assertNotNull("Editor PDF asli tidak dibuka", opened)
                assertTrue("Pemilih PDF tidak dipanggil", picker.hits > 0)
                assertArrayEquals(original, source.readBytes())
            }
        } finally {
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(picker)
            instrumentation.removeMonitor(monitor)
            source.delete()
        }
    }

    @Test fun nativeEditorCanReplaceOriginalTextInWorkingCopy() {
        val source = samplePdf()
        val before = source.readBytes()
        try {
            val intent = Intent(context, NativePdfActivity::class.java).setData(uri(source))
                .putExtra(NativePdfActivity.EXTRA_CAN_WRITE, true)
            ActivityScenario.launch<NativePdfActivity>(intent).use { editor ->
                var opened = false
                repeat(150) {
                    if (!opened) {
                        editor.onActivity { activity ->
                            findDescription(activity.window.decorView, "Edit teks PDF: Nama Lama")?.let { row ->
                                opened = row.performClick()
                            }
                        }
                        if (!opened) Thread.sleep(100)
                    }
                }
                assertTrue("Segmen teks asli tidak ditampilkan", opened)
                val input = waitForAccessible { it.contentDescription?.toString() == "Teks PDF pengganti" }
                assertNotNull("Dialog penggantian tidak tampil", input)
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Nama Baru")
                }
                assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                instrumentation.waitForIdleSync()
                val apply = waitForAccessible { it.text?.toString() == "Terapkan" }
                assertNotNull("Tombol terapkan tidak tampil", apply)
                tap(apply!!)
                var changed = false
                repeat(150) {
                    if (!changed) {
                        editor.onActivity { activity ->
                            changed = findDescription(activity.window.decorView, "Edit teks PDF: Nama Baru") != null
                        }
                        if (!changed) Thread.sleep(100)
                    }
                }
                assertTrue("Segmen baru tidak tampil setelah diedit", changed)
                assertArrayEquals("File sumber tidak diubah sebelum disimpan", before, source.readBytes())
            }
        } finally { source.delete() }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        assertFalse("Tombol tidak terlihat", bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
        finally { down.recycle(); up.recycle() }
        instrumentation.waitForIdleSync()
    }

    private fun waitForAccessible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        repeat(150) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            findAccessible(root, predicate)?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun findAccessible(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) findAccessible(node.getChild(i), predicate)?.let { return it }
        return null
    }

    private fun findDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findDescription(view.getChildAt(i), description)?.let { return it }
        }
        return null
    }
}
