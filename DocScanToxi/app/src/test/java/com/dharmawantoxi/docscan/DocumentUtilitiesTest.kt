package com.dharmawantoxi.docscan

import com.dharmawantoxi.docscan.data.DocumentNames
import com.dharmawantoxi.docscan.data.PdfGeometry
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

    @Test(expected = IllegalArgumentException::class)
    fun pdfRejectsEmptyImage() { PdfGeometry.fit(0, 10) }
}
