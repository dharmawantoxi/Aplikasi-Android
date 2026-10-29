package com.dharmawantoxi.docscan

import com.dharmawantoxi.docscan.data.DocumentNames
import com.dharmawantoxi.docscan.data.PdfGeometry
import com.dharmawantoxi.docscan.data.PdfPageFormat
import org.junit.Assert.*
import org.junit.Test

class DocumentUtilitiesTest {
    @Test fun namesAreSafeForExport() {
        assertEquals("KTP_2026_", DocumentNames.fileName(" KTP/2026? "))
        assertEquals("Dokumen", DocumentNames.fileName(" ... "))
        assertEquals("Dokumen tanpa nama", DocumentNames.displayName("  "))
        assertEquals(80, DocumentNames.fileName("x".repeat(100)).length)
    }

    @Test fun pdfFittingKeepsAspectRatioAndMargins() {
        val portrait = PdfGeometry.fit(1200, 2400)
        assertTrue(portrait.left >= 24f && portrait.top >= 24f)
        assertTrue(portrait.right <= 571f && portrait.bottom <= 818f)
        assertEquals(0.5f, (portrait.right - portrait.left) / (portrait.bottom - portrait.top), 0.001f)
        val landscape = PdfGeometry.fit(2400, 1200)
        assertEquals(24f, landscape.left, 0.001f)
        assertEquals(2f, (landscape.right - landscape.left) / (landscape.bottom - landscape.top), 0.001f)
    }

    @Test fun pdfDimensionsFollowEditsWithoutDrift() {
        val format = PdfPageFormat(300, 500)
        assertEquals(format, PdfGeometry.editedPageFormat(format, 1440, 2400, 1440, 2400))
        assertEquals(PdfPageFormat(500, 300), PdfGeometry.editedPageFormat(format, 1440, 2400, 2400, 1440))
        assertEquals(PdfPageFormat(150, 500), PdfGeometry.editedPageFormat(format, 1440, 2400, 720, 2400))
        // Halaman sangat sempit dibulatkan menjadi satu piksel, tetapi tetap akurat jika hanya ditambah teks atau diputar.
        val narrow = PdfPageFormat(2, 14400)
        assertEquals(narrow, PdfGeometry.editedPageFormat(narrow, 1, 2400, 1, 2400))
        assertEquals(PdfPageFormat(14400, 2), PdfGeometry.editedPageFormat(narrow, 1, 2400, 2400, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pdfRejectsEmptyImage() { PdfGeometry.fit(0, 10) }
}
