package com.dharmawantoxi.docscan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dharmawantoxi.docscan.pdf.PdfMerge
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfMergeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun init() { PDFBoxResourceLoader.init(context) }

    private fun sample(file: File, widths: List<Float>, signed: Boolean = false) {
        PDDocument().use { pdf ->
            widths.forEach { width ->
                val page = PDPage(PDRectangle(width, 500f))
                page.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 10f, 30f, 20f)
                    action = PDActionURI().apply { uri = "https://example.com" }
                })
                pdf.addPage(page)
            }
            if (signed) pdf.documentCatalog.cosObject.setItem(COSName.getPDFName("Perms"), COSDictionary().apply {
                setItem(COSName.getPDFName("DocMDP"), COSDictionary())
            })
            pdf.save(file)
        }
    }

    @Test fun mergePreservesPageOrderAnnotationsAndInputs() {
        val dir = File(context.cacheDir, "merge-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val a = File(dir, "a.pdf"); val b = File(dir, "b.pdf"); val out = File(dir, "out.pdf")
            sample(a, listOf(200f, 300f)); sample(b, listOf(400f))
            val aBefore = a.readBytes(); val bBefore = b.readBytes()
            PdfMerge.merge(listOf(b, a), out)
            PDDocument.load(out).use { pdf ->
                assertEquals(3, pdf.numberOfPages)
                assertEquals(listOf(400f, 200f, 300f), (0..2).map { pdf.getPage(it).mediaBox.width })
                assertEquals(1, pdf.getPage(0).annotations.size)
                val link = pdf.getPage(0).annotations.single() as PDAnnotationLink
                assertEquals("https://example.com", (link.action as PDActionURI).uri)
            }
            assertArrayEquals(aBefore, a.readBytes()); assertArrayEquals(bBefore, b.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsInvalidSignedTooManyPagesAndSourceAsOutput() {
        val dir = File(context.cacheDir, "merge-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val a = File(dir, "a.pdf"); val signed = File(dir, "signed.pdf")
            val tooMany = File(dir, "many.pdf"); val invalid = File(dir, "invalid.pdf"); val out = File(dir, "out.pdf")
            val locked = File(dir, "locked.pdf")
            InstrumentationRegistry.getInstrumentation().context.assets.open("password-protected.pdf").use { input ->
                locked.outputStream().use { output -> input.copyTo(output) }
            }
            sample(a, listOf(200f)); sample(signed, listOf(300f), signed = true)
            sample(tooMany, List(PdfMerge.MAX_PAGES) { 100f }); invalid.writeText("not a PDF")
            fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IOException) { } }
            rejects { PdfMerge.merge(listOf(a, signed), out) }; assertFalse(out.exists())
            rejects { PdfMerge.merge(listOf(a, invalid), out) }; assertFalse(out.exists())
            rejects { PdfMerge.merge(listOf(a, locked), out) }; assertFalse(out.exists())
            rejects { PdfMerge.merge(listOf(a, tooMany), out) }; assertFalse(out.exists())
            val before = a.readBytes()
            try { PdfMerge.merge(listOf(a, signed), a); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(before, a.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
