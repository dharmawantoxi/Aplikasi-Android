package com.dharmawantoxi.docscan.pdf

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class PdfOverwriteException(val restored: Boolean, cause: Throwable) : IOException(
    if (restored) "Gagal mengganti PDF. File asli berhasil dipulihkan dari cadangan."
    else "Gagal mengganti PDF dan pemulihan otomatis gagal. File asli mungkin rusak; gunakan cadangan yang tadi disimpan.", cause
)

/** Penulisan diverifikasi; penyedia dokumen tidak selalu menyediakan rename atomik. */
object VerifiedPdfOutput {
    private const val MAX_VERIFY_BYTES = 400L * 1024 * 1024

    fun hashFile(file: File): String = file.inputStream().use(::hash)

    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_VERIFY_BYTES) throw IOException("File terlalu besar untuk diverifikasi")
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun hashDestination(openRead: () -> InputStream?): String {
        val input = openRead() ?: throw IOException("File tidak dapat dibaca untuk verifikasi")
        return input.use(::hash)
    }

    fun copyAndVerify(source: File, expectedHash: String,
                      openRead: () -> InputStream?, openWrite: () -> OutputStream?) {
        if (hashFile(source) != expectedHash) throw IOException("Salinan lokal tidak sesuai; penulisan dibatalkan")
        source.inputStream().use { input ->
            val output = openWrite() ?: throw IOException("Lokasi penyimpanan tidak dapat dibuka untuk ditulis")
            output.use { input.copyTo(it) }
        }
        if (hashDestination(openRead) != expectedHash) {
            throw IOException("Isi file yang tersimpan tidak cocok dengan hasil yang diharapkan")
        }
    }

    /** Kembalikan hash versi baru. File asal harus sama dengan saat pertama dibuka. */
    fun replaceWithRecovery(original: File, edited: File, sourceHash: String,
                            openRead: () -> InputStream?, openWrite: () -> OutputStream?): String {
        if (hashFile(original) != sourceHash) throw IOException("Cadangan lokal rusak; file asli tidak diganti")
        if (hashDestination(openRead) != sourceHash) {
            throw IOException("File asli telah berubah sejak dibuka. Buka ulang atau simpan hasil sebagai salinan.")
        }
        val newHash = hashFile(edited)
        var writeAttempted = false
        try {
            copyAndVerify(edited, newHash, openRead) {
                // Provider bisa saja menimpa file saat membuka stream, bahkan jika ia gagal.
                writeAttempted = true
                openWrite()
            }
        } catch (failure: Exception) {
            if (!writeAttempted) throw IOException("Gagal menyiapkan hasil. File asli belum ditulis.", failure)
            val restored = runCatching { copyAndVerify(original, sourceHash, openRead, openWrite) }.isSuccess
            throw PdfOverwriteException(restored, failure)
        }
        return newHash
    }
}
