package com.dharmawantoxi.docscan.zip

import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure file operations. Work only on private copies, never directly on a document provider. */
object ZipArchive {
    const val MAX_FILES = 100
    const val MAX_ENTRIES = 200
    const val MAX_INPUT_BYTES = 100L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 110L * 1024 * 1024
    const val MAX_EXTRACT_BYTES = 200L * 1024 * 1024
    private const val MAX_DEPTH = 12

    data class Input(val file: File, val name: String)
    data class Entry(val path: String, val directory: Boolean)

    /** Names are numbered to avoid collisions and preserve the order chosen by the user. */
    fun create(inputs: List<Input>, output: File) {
        require(inputs.size in 1..MAX_FILES) { "Pilih 1–$MAX_FILES file" }
        require(inputs.none { it.file.canonicalFile == output.canonicalFile }) { "Hasil harus berbeda dari sumber" }
        if (inputs.any { !it.file.isFile } || inputs.sumOf { it.file.length() } > MAX_INPUT_BYTES)
            throw IOException("File tidak tersedia atau total ukuran melebihi 100 MB")
        val names = inputs.mapIndexed { i, input -> "${i + 1}_${safeBaseName(input.name)}" }
        val checkDir = File(output.parentFile, "zip-check-${UUID.randomUUID()}")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                inputs.forEachIndexed { i, input ->
                    zip.putNextEntry(ZipEntry(names[i]))
                    input.file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            if (output.length() > MAX_ARCHIVE_BYTES) throw IOException("Arsip ZIP terlalu besar")
            if (!checkDir.mkdirs()) throw IOException("Folder pemeriksaan tidak dapat dibuat")
            val entries = extract(output, checkDir)
            if (entries.map { it.path } != names) throw IOException("Daftar isi ZIP tidak sesuai")
            inputs.forEachIndexed { i, input ->
                if (!hash(input.file).contentEquals(hash(File(checkDir, names[i])))) throw IOException("Isi ZIP tidak sesuai")
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally { checkDir.deleteRecursively() }
    }

    /** Validates all names and checksums before the caller writes anything to a provider. */
    fun extract(archive: File, destination: File): List<Entry> {
        if (!archive.isFile || archive.length() > MAX_ARCHIVE_BYTES) throw IOException("ZIP tidak tersedia atau melebihi 110 MB")
        if (!destination.isDirectory || destination.list()?.isNotEmpty() != false) throw IOException("Folder kerja harus kosong")
        val root = destination.canonicalFile
        val seen = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val directories = mutableSetOf<String>()
        val spellings = mutableMapOf<String, String>()
        val result = mutableListOf<Entry>()
        var total = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (result.size >= MAX_ENTRIES) throw IOException("ZIP melebihi $MAX_ENTRIES entri")
                val entry = entries.nextElement()
                val isDir = entry.isDirectory
                val path = cleanPath(entry.name, isDir)
                val components = path.split('/')
                val key = key(path)
                for (i in 1..components.size) {
                    val part = components.take(i).joinToString("/")
                    val old = spellings.putIfAbsent(key(part), part)
                    if (old != null && old != part) throw IOException("Nama dalam ZIP ambigu: $part")
                    if (spellings.size > 500) throw IOException("Terlalu banyak file dan folder dalam ZIP")
                }
                if (!seen.add(key)) throw IOException("Nama dalam ZIP duplikat: $path")
                for (i in 1 until components.size) {
                    val parentKey = key(components.take(i).joinToString("/"))
                    if (parentKey in files) throw IOException("Konflik folder dalam ZIP")
                    directories.add(parentKey)
                }
                if (isDir) {
                    if (key in files) throw IOException("Konflik folder dalam ZIP")
                    directories.add(key)
                } else {
                    if (key in directories) throw IOException("Konflik file dalam ZIP")
                    files.add(key)
                }
                val target = File(root, path).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw IOException("Jalur ZIP tidak aman")
                if (isDir) {
                    if (entry.size > 0) throw IOException("Entri folder ZIP tidak valid")
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
                } else {
                    if (entry.size < 0 || entry.size > MAX_EXTRACT_BYTES - total) throw IOException("Isi ZIP melebihi 200 MB")
                    val parent = target.parentFile ?: throw IOException("Jalur ZIP tidak valid")
                    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
                    val crc = CRC32()
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                written += count; total += count
                                if (total > MAX_EXTRACT_BYTES || written > entry.size) throw IOException("Isi ZIP melebihi batas")
                                crc.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (written != entry.size || crc.value != entry.crc) throw IOException("Isi ZIP rusak: $path")
                }
                result.add(Entry(path, isDir))
            }
        }
        return result
    }

    private fun cleanPath(name: String, directory: Boolean): String {
        if (name.startsWith('/') || '\\' in name || name.length > 512) throw IOException("Jalur ZIP tidak aman")
        val path = if (directory && name.endsWith('/')) name.dropLast(1) else name
        val parts = path.split('/')
        if (parts.size !in 1..MAX_DEPTH || parts.any { part ->
                part.isEmpty() || part == "." || part == ".." || part.length > 120 ||
                    part.endsWith(' ') || part.endsWith('.') || part.any { it.code < 32 || it.code == 127 || it in ":*?\"<>|" }
            }) throw IOException("Jalur ZIP tidak aman: $name")
        return path
    }

    private fun key(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun safeBaseName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.code < 32 || it.code == 127 || it in ":*?\"<>|/\\") '_' else it }
            .joinToString("").take(100).trim().trimEnd('.')
        return base.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"
    }

    private fun hash(file: File): ByteArray = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; if (n > 0) digest.update(buf, 0, n) }
        digest.digest()
    }
}
