package com.dharmawantoxi.docscan

import com.dharmawantoxi.docscan.pdf.PdfOverwriteException
import com.dharmawantoxi.docscan.pdf.VerifiedPdfOutput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class VerifiedPdfOutputTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(name: String, content: String): File = temp.newFile(name).apply { writeText(content) }

    @Test fun replaceAndVerify() {
        val original = file("backup", "PDF asli")
        val edited = file("edited", "PDF baru yang lebih panjang")
        val target = file("target", original.readText())
        val hash = VerifiedPdfOutput.hashFile(original)
        val result = VerifiedPdfOutput.replaceWithRecovery(original, edited, hash,
            { target.inputStream() }, { target.outputStream() })
        assertEquals(VerifiedPdfOutput.hashFile(edited), result)
        assertEquals(edited.readText(), target.readText())
        assertEquals("PDF asli", original.readText())
    }

    @Test fun concurrentChangeDoesNotOpenTargetForWriting() {
        val original = file("backup", "asli")
        val edited = file("edited", "hasil")
        val target = file("target", "diubah pihak lain")
        var writes = 0
        val error = runCatching {
            VerifiedPdfOutput.replaceWithRecovery(original, edited, VerifiedPdfOutput.hashFile(original),
                { target.inputStream() }, { writes++; target.outputStream() })
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("berubah sejak dibuka"))
        assertEquals(0, writes)
        assertEquals("diubah pihak lain", target.readText())
    }

    @Test fun partialWriteIsRolledBackFromBackup() {
        val original = file("backup", "PDF asli")
        val edited = file("edited", "PDF baru")
        val target = file("target", original.readText())
        var writes = 0
        val error = runCatching {
            VerifiedPdfOutput.replaceWithRecovery(original, edited, VerifiedPdfOutput.hashFile(original),
                { target.inputStream() }, {
                    writes++
                    if (writes == 1) failingOutput(target) else target.outputStream()
                })
        }.exceptionOrNull()
        assertTrue(error is PdfOverwriteException && error.restored)
        assertTrue(error!!.message.orEmpty().contains("berhasil dipulihkan"))
        assertEquals(2, writes)
        assertEquals(original.readText(), target.readText())
    }

    @Test fun failedRecoveryWarnsAboutExternalBackup() {
        val original = file("backup", "PDF asli")
        val edited = file("edited", "PDF baru")
        val target = file("target", original.readText())
        val error = runCatching {
            VerifiedPdfOutput.replaceWithRecovery(original, edited, VerifiedPdfOutput.hashFile(original),
                { target.inputStream() }, { failingOutput(target) })
        }.exceptionOrNull()
        assertTrue(error is PdfOverwriteException && !error.restored)
        assertTrue(error!!.message.orEmpty().contains("gunakan cadangan"))
        assertEquals("PDF asli", original.readText())
    }

    @Test fun unverifiableOutputIsAlsoRestored() {
        val original = file("backup", "PDF asli")
        val edited = file("edited", "PDF baru")
        val target = file("target", original.readText())
        var reads = 0
        val error = runCatching {
            VerifiedPdfOutput.replaceWithRecovery(original, edited, VerifiedPdfOutput.hashFile(original),
                {
                    reads++
                    if (reads == 2) ByteArrayInputStream("tidak cocok".toByteArray()) else target.inputStream()
                }, { target.outputStream() })
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(original.readText(), target.readText())
        assertTrue(error!!.message.orEmpty().contains("berhasil dipulihkan"))
    }

    @Test fun corruptBackupPreventsAnyWrite() {
        val original = file("backup", "versi cadangan")
        val edited = file("edited", "PDF baru")
        val target = file("target", "PDF asli")
        val expected = VerifiedPdfOutput.hashFile(target)
        var writes = 0
        val error = runCatching {
            VerifiedPdfOutput.replaceWithRecovery(original, edited, expected,
                { target.inputStream() }, { writes++; target.outputStream() })
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, writes)
        assertEquals("PDF asli", target.readText())
    }

    @Test fun invalidCopySourceNeverOpensDestination() {
        val source = file("source", "hasil yang berubah")
        val target = file("target", "data penting")
        var writes = 0
        val error = runCatching {
            VerifiedPdfOutput.copyAndVerify(source, "hash tidak cocok", { target.inputStream() },
                { writes++; target.outputStream() })
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, writes)
        assertEquals("data penting", target.readText())
    }

    private fun failingOutput(target: File): OutputStream {
        val stream = target.outputStream()
        return object : OutputStream() {
            override fun write(value: Int) { stream.write(value); throw IOException("Simulasi gagal menulis") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > 0) stream.write(bytes, offset, 1)
                throw IOException("Simulasi gagal menulis")
            }
            override fun close() = stream.close()
        }
    }
}
