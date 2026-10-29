package com.dharmawantoxi.docscan.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException

/** Operates on private copies; never writes to an input. PDFBoxResourceLoader must be initialized. */
object PdfMerge {
    const val MAX_FILES = 20
    const val MAX_PAGES = 100
    const val MAX_INPUT_BYTES = 100L * 1024 * 1024

    fun merge(inputs: List<File>, output: File) {
        require(inputs.size in 2..MAX_FILES) { "Pilih 2–$MAX_FILES file PDF" }
        require(inputs.none { it.canonicalFile == output.canonicalFile }) { "Hasil harus berbeda dari sumber" }
        if (inputs.any { !it.isFile || it.length() == 0L } || inputs.sumOf { it.length() } > MAX_INPUT_BYTES)
            throw IOException("File kosong atau total ukuran melebihi 100 MB")
        val scratch = output.parentFile ?: throw IOException("Folder kerja tidak tersedia")
        val memory = { MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch) }
        var pages = 0
        try {
            PDDocument(memory()).use { result ->
                val merger = PDFMergerUtility()
                inputs.forEachIndexed { index, file ->
                    try {
                        PDDocument.load(file, memory()).use { source ->
                            if (source.isEncrypted || !source.currentAccessPermission.canAssembleDocument())
                                throw IOException("PDF dilindungi atau tidak diizinkan untuk digabung")
                            val perms = source.documentCatalog.cosObject.getCOSDictionary(COSName.getPDFName("Perms"))
                            if (source.signatureDictionaries.isNotEmpty() ||
                                listOf("DocMDP", "UR", "UR3", "FieldMDP").any { perms?.getDictionaryObject(COSName.getPDFName(it)) != null })
                                throw IOException("PDF bertanda tangan digital tidak didukung")
                            if (source.numberOfPages < 1) throw IOException("PDF tidak memiliki halaman")
                            pages += source.numberOfPages
                            if (pages > MAX_PAGES) throw IOException("Total halaman melebihi $MAX_PAGES")
                            merger.appendDocument(result, source)
                        }
                    } catch (e: Exception) {
                        throw IOException("File ke-${index + 1} tidak dapat digabung: ${e.localizedMessage.orEmpty()}", e)
                    }
                }
                result.save(output)
            }
            PDDocument.load(output, memory()).use {
                if (it.numberOfPages != pages || it.isEncrypted) throw IOException("Hasil PDF tidak lolos pemeriksaan halaman")
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }
}
