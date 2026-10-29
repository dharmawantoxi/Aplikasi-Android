package com.dharmawantoxi.docscan

import com.dharmawantoxi.docscan.zip.ZipArchive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun zip(entries: List<Pair<String, String>>): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path)); out.write(content.toByteArray()); out.closeEntry()
            }
        }
        return file
    }
    private fun extract(zip: File) = ZipArchive.extract(zip, temporary.newFolder())
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected IOException") } catch (_: IOException) { } }

    @Test fun createsAndExtractsWithSafeUniqueNamesAndOriginalsUnchanged() {
        val a = temporary.newFile().apply { writeText("Hello") }
        val b = temporary.newFile().apply { writeText("World") }
        val output = File(temporary.root, "out.zip")
        ZipArchive.create(listOf(ZipArchive.Input(a, "cat.txt"), ZipArchive.Input(b, "cat.txt")), output)
        val target = temporary.newFolder()
        val entries = ZipArchive.extract(output, target)
        assertEquals(listOf("1_cat.txt", "2_cat.txt"), entries.map { it.path })
        assertEquals("Hello", File(target, "1_cat.txt").readText())
        assertEquals("World", File(target, "2_cat.txt").readText())
        assertEquals("Hello", a.readText()); assertEquals("World", b.readText())
        ZipFile(output).use { assertEquals(2, it.size()) }
    }

    @Test fun nestedAndEmptyDirectories() {
        val archive = zip(listOf("a/" to "", "a/b/" to "", "a/b/file.txt" to "ok", "empty/" to ""))
        val target = temporary.newFolder()
        assertEquals(4, ZipArchive.extract(archive, target).size)
        assertEquals("ok", File(target, "a/b/file.txt").readText())
        assertTrue(File(target, "empty").isDirectory)
        val empty = zip(emptyList())
        assertTrue(extract(empty).isEmpty())
    }

    @Test fun rejectsTraversalAndAbsoluteOrAmbiguousPaths() {
        for (path in listOf("../evil", "/absolute", "a/../evil", "a\\b", "C:/evil", "a//b", "a/./b", "a /b", "a/" + "x".repeat(121))) {
            val archive = zip(listOf(path to "x"))
            rejected { extract(archive) }
        }
    }

    @Test fun rejectsCaseAndDirectoryFileConflicts() {
        rejected { extract(zip(listOf("x.txt" to "1", "X.txt" to "2"))) }
        rejected { extract(zip(listOf("a" to "1", "a/b.txt" to "2"))) }
        rejected { extract(zip(listOf("a/b.txt" to "1", "a" to "2"))) }
        rejected { extract(zip(listOf("Dir/a.txt" to "1", "dir/b.txt" to "2"))) }
        rejected { extract(zip(listOf("é.txt" to "1", "e\u0301.txt" to "2"))) }
    }

    @Test fun rejectsCorruptionAndNonZip() {
        rejected { extract(temporary.newFile().apply { writeText("not a zip") }) }
        val file = temporary.newFile()
        val bytes = "abcdefgh".toByteArray()
        val crc = CRC32().apply { update(bytes) }
        ZipOutputStream(file.outputStream()).use { out ->
            val entry = ZipEntry("test.txt").apply { method = ZipEntry.STORED; size = bytes.size.toLong(); this.crc = crc.value }
            out.putNextEntry(entry); out.write(bytes); out.closeEntry()
        }
        val raw = file.readBytes(); val index = raw.indexOf(bytes[0]); assertTrue(index > 0)
        raw[index] = 'z'.code.toByte(); file.writeBytes(raw)
        rejected { extract(file) }
    }

    @Test fun rejectsTooManyEntriesAndDepth() {
        rejected { extract(zip((1..ZipArchive.MAX_ENTRIES + 1).map { "f$it" to "" })) }
        rejected { extract(zip(listOf((1..13).joinToString("/") { "x$it" } to ""))) }
    }

    @Test fun enforcesArchiveAndInputSizeLimits() {
        val oversized = temporary.newFile()
        java.io.RandomAccessFile(oversized, "rw").use { it.setLength(ZipArchive.MAX_ARCHIVE_BYTES + 1) }
        rejected { extract(oversized) }
        val input = temporary.newFile()
        java.io.RandomAccessFile(input, "rw").use { it.setLength(ZipArchive.MAX_INPUT_BYTES + 1) }
        val output = File(temporary.root, "too-big.zip")
        rejected { ZipArchive.create(listOf(ZipArchive.Input(input, "large")), output) }
        assertFalse(output.exists())
    }

    @Test fun emptySourceAndSafeBasenames() {
        val file = temporary.newFile()
        val output = File(temporary.root, "result.zip")
        ZipArchive.create(listOf(ZipArchive.Input(file, "../bad:name.txt")), output)
        ZipFile(output).use { assertEquals("1_bad_name.txt", it.entries().nextElement().name) }
        try { ZipArchive.create(listOf(ZipArchive.Input(file, "file")), file); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
    }
}
