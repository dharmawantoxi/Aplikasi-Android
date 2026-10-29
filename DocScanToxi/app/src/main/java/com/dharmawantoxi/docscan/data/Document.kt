package com.dharmawantoxi.docscan.data

/** Dokumen dan urutan halamannya; isi gambar tersimpan di penyimpanan privat aplikasi. */
data class Document(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<String>
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
