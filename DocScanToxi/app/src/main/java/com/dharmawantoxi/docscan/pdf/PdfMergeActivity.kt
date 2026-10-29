package com.dharmawantoxi.docscan.pdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Add inputs one at a time so their order is explicit even on different document providers. */
class PdfMergeActivity : BaseActivity() {
    private val sources = mutableListOf<Uri>()
    private lateinit var list: LinearLayout
    private var running = false

    private val pickSource = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            if (uri in sources) { error(tr("File ini sudah ada dalam daftar.", "This file is already in the list.")); return@registerForActivityResult }
            if (sources.size >= PdfMerge.MAX_FILES) { error(tr("Maksimal ${PdfMerge.MAX_FILES} file.", "Maximum ${PdfMerge.MAX_FILES} files.")); return@registerForActivityResult }
            val read = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (read != 0) runCatching { contentResolver.takePersistableUriPermission(uri, read) }
            sources.add(uri)
            render()
        }
    }
    private val pickOutput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) save(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        savedInstanceState?.getStringArrayList("sources")?.forEach { sources.add(Uri.parse(it)) }
        buildScreen()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("sources", ArrayList(sources.map { it.toString() }))
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        val dp = { n: Int -> Ui.dp(this, n) }
        val root = Ui.column(this).apply { setBackgroundColor(Ui.color(context, R.color.md_background)) }
        val bar = Ui.row(this)
        bar.addView(Ui.iconButton(this, R.drawable.ic_close, tr("Tutup gabung PDF", "Close PDF merge")).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(Ui.text(this, tr("Gabung PDF", "Merge PDFs"), 19f, Ui.color(this, R.color.md_on_surface), true))
        root.addView(bar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = Ui.column(this).apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(Ui.text(this, tr("Tambahkan PDF satu per satu. Atur urutan dengan tombol Naik dan Turun. Hasil disimpan sebagai file terpisah; jangan pilih file sumber sebagai tujuan.", "Add PDFs one at a time. Use Up and Down to change their order. The result is saved as a separate file; do not choose a source file as the destination."), 14f, Ui.color(this, R.color.md_on_surface_variant)))
        content.addView(Ui.text(this, tr("2–20 file, total maksimal 100 MB dan 100 halaman. PDF berkunci, dibatasi, atau bertanda tangan tidak didukung. Periksa formulir, tautan, penanda, dan metadata pada hasil; fitur tersebut mungkin berubah.", "2–20 files, up to 100 MB and 100 pages in total. Locked, restricted, or signed PDFs are not supported. Check forms, links, bookmarks, and metadata in the result; they may change."), 13f, Ui.color(this, R.color.md_on_surface_variant)).apply { setPadding(0, dp(12), 0, dp(12)) })
        content.addView(Ui.button(this, tr("Tambah PDF", "Add PDF"), outlined = true).apply { setOnClickListener { chooseSource() } }, LinearLayout.LayoutParams(-1, -2))
        list = Ui.column(this)
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(Ui.button(this, tr("Gabung dan simpan", "Merge and save")).apply { setOnClickListener { chooseOutput() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setScreen(root)
    }

    private fun render() {
        list.removeAllViews()
        if (sources.isEmpty()) list.addView(Ui.text(this, tr("Belum ada PDF dipilih.", "No PDFs selected yet."), 14f, Ui.color(this, R.color.md_on_surface_variant)))
        sources.forEachIndexed { index, uri ->
            val dp = { n: Int -> Ui.dp(this, n) }
            val entry = Ui.column(this).apply { setPadding(0, dp(8), 0, dp(8)) }
            entry.addView(Ui.text(this, "${index + 1}. ${name(uri)}", 15f, Ui.color(this, R.color.md_on_surface), true))
            val controls = Ui.row(this)
            controls.addView(Ui.button(this, tr("Naik", "Up"), outlined = true).apply {
                isEnabled = index > 0
                setOnClickListener { java.util.Collections.swap(sources, index, index - 1); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(Ui.button(this, tr("Turun", "Down"), outlined = true).apply {
                isEnabled = index < sources.lastIndex
                setOnClickListener { java.util.Collections.swap(sources, index, index + 1); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(Ui.button(this, tr("Hapus", "Delete"), outlined = true).apply {
                setOnClickListener { sources.removeAt(index); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(controls)
            list.addView(entry)
        }
    }

    private fun name(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "PDF"
    } catch (_: Exception) { "PDF" }

    private fun chooseSource() {
        if (sources.size >= PdfMerge.MAX_FILES) { error(tr("Maksimal ${PdfMerge.MAX_FILES} file.", "Maximum ${PdfMerge.MAX_FILES} files.")); return }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { pickSource.launch(picker) } catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia.", "No document picker is available.")) }
    }

    private fun chooseOutput() {
        if (running) return
        if (sources.size < 2) { error(tr("Tambahkan minimal dua file PDF.", "Add at least two PDF files.")); return }
        AlertDialog.Builder(this).setTitle(tr("Simpan hasil gabungan", "Save merged PDF"))
            .setMessage(tr("Pilih nama dan lokasi baru, bukan salah satu PDF sumber. Jika memilih file lain yang sudah ada, file tersebut dapat ditimpa. Sumber tidak sengaja terpilih akan diblokir jika dapat dikenali. Penyimpanan yang gagal dapat meninggalkan file hasil tidak lengkap.", "Choose a new name and location, not a source PDF. An existing file may be overwritten if selected. A source selected by mistake is blocked when recognizable. A failed save may leave an incomplete file."))
            .setNegativeButton(tr("Batal", "Cancel"), null).setPositiveButton(tr("Pilih lokasi", "Choose location")) { _, _ ->
                val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, tr("Gabungan.pdf", "Merged.pdf"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                try { pickOutput.launch(picker) } catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia.", "No document picker is available.")) }
            }.show()
    }

    private fun save(destination: Uri) {
        if (running) return
        val selected = sources.toList()
        if (selected.size < 2) { error(tr("Tambahkan minimal dua file PDF.", "Add at least two PDF files.")); return }
        if (destination in selected) { error(tr("Tujuan tidak boleh sama dengan file sumber. Pilih nama baru.", "The destination must not be a source file. Choose a new name.")); return }
        running = true
        busy(true, tr("Menggabungkan PDF…", "Merging PDFs…"))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { mergeAndSave(selected, destination) }
                running = false; busy(false)
                AlertDialog.Builder(this@PdfMergeActivity).setTitle(tr("PDF berhasil digabung", "PDFs merged successfully"))
                    .setMessage(tr("Hasil telah disimpan dan diverifikasi. Periksa isi dan tata letak hasil di pembaca PDF.", "The result was saved and verified. Check its contents and layout in a PDF reader."))
                    .setPositiveButton(tr("Selesai", "Done"), null).show()
            } catch (e: Exception) {
                running = false; busy(false)
                error(tr("PDF tidak dapat digabung atau disimpan. File tujuan mungkin belum lengkap; pilih nama baru dan coba lagi.", "The PDFs could not be merged or saved. The destination file may be incomplete; choose a new name and try again."), e)
            }
        }
    }

    private fun mergeAndSave(selected: List<Uri>, destination: Uri) {
        val dir = File(cacheDir, "pdf-merge-${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
        try {
            var total = 0L
            val files = selected.mapIndexed { i, uri ->
                val file = File(dir, "input-$i.pdf")
                (contentResolver.openInputStream(uri) ?: throw IOException(tr("PDF ke-${i + 1} tidak dapat dibuka", "PDF ${i + 1} could not be opened"))).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > PdfMerge.MAX_INPUT_BYTES) throw IOException("Total ukuran PDF melebihi 100 MB")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                file
            }
            val result = File(dir, "result.pdf")
            PdfMerge.merge(files, result)
            val hashes = files.map(VerifiedPdfOutput::hashFile).toMutableSet()
            // Check the current sources as well as snapshots before touching the destination.
            selected.forEach { uri -> hashes.add(VerifiedPdfOutput.hashDestination { contentResolver.openInputStream(uri) }) }
            val read = { contentResolver.openInputStream(destination) }
            val destinationHash = VerifiedPdfOutput.hashDestination(read)
            if (destinationHash in hashes) throw IOException("Tujuan sama dengan atau identik dengan PDF sumber; pilih file baru")
            val hash = VerifiedPdfOutput.hashFile(result)
            VerifiedPdfOutput.copyAndVerify(result, hash, read) { contentResolver.openOutputStream(destination, "wt") }
        } finally { dir.deleteRecursively() }
    }
}
