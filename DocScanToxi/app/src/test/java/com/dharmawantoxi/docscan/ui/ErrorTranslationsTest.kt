package com.dharmawantoxi.docscan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTranslationsTest {
    @Test fun preservesCriticalRecoveryInformation() {
        val failed = ErrorTranslations.english("Gagal mengganti PDF dan pemulihan otomatis gagal. File asli mungkin rusak; gunakan cadangan yang tadi disimpan.")
        assertTrue(failed.contains("recovery failed"))
        assertTrue(failed.contains("original file may be damaged"))
        val restored = ErrorTranslations.english("Gagal mengganti PDF. File asli berhasil dipulihkan dari cadangan.")
        assertTrue(restored.contains("successfully restored"))
    }

    @Test fun retainsUnknownDetailsAndNestedFileErrors() {
        assertEquals("Provider error 123", ErrorTranslations.english("Provider error 123"))
        assertEquals("File 2 could not be merged: Digitally signed PDFs are not supported",
            ErrorTranslations.english("File ke-2 tidak dapat digabung: PDF bertanda tangan digital tidak didukung"))
        assertEquals("Unsafe ZIP path: ../name", ErrorTranslations.english("Jalur ZIP tidak aman: ../name"))
    }
}
