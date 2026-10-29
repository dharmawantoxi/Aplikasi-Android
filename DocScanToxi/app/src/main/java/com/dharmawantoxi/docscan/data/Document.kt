package com.dharmawantoxi.docscan.data

/** Ukuran halaman PDF dalam satuan point (1/72 inci). */
data class PdfPageFormat(val width: Int, val height: Int) {
    companion object { const val MAX_POINTS = 14400 }
}

/** Dokumen dan urutan halamannya; isi gambar tersimpan di penyimpanan privat aplikasi. */
data class Document(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<String>,
    // Hanya halaman hasil impor PDF memiliki ukuran asli. Dokumen lama tetap memakai A4.
    val pageFormats: Map<String, PdfPageFormat> = emptyMap()
)

object DocumentNames {
    fun displayName(value: String): String = value.trim().take(100).ifBlank { "Dokumen tanpa nama" }

    /** Nama aman untuk disarankan kepada pemilih file Android. */
    fun fileName(value: String): String = value
        .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
        .trim(' ', '.')
        .take(80)
        .ifBlank { "Dokumen" }
}
