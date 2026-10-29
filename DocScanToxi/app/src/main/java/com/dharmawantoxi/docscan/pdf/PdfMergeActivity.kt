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
            if (uri in sources) { error("File ini sudah ada dalam daftar."); return@registerForActivityResult }
            if (sources.size >= PdfMerge.MAX_FILES) { error("Maksimal ${PdfMerge.MAX_FILES} file."); return@registerForActivityResult }
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
        bar.addView(Ui.iconButton(this, R.drawable.ic_close, "Tutup gabung PDF").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(Ui.text(this, "Gabung PDF", 19f, Ui.color(this, R.color.md_on_surface), true))
        root.addView(bar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = Ui.column(this).apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(Ui.text(this, "Tambahkan PDF satu per satu. Atur urutan dengan tombol Naik dan Turun. Hasil disimpan sebagai file terpisah; jangan pilih file sumber sebagai tujuan.", 14f, Ui.color(this, R.color.md_on_surface_variant)))
        content.addView(Ui.text(this, "2–20 file, total maksimal 100 MB dan 100 halaman. PDF berkunci, dibatasi, atau bertanda tangan tidak didukung. Periksa formulir, tautan, penanda, dan metadata pada hasil; fitur tersebut mungkin berubah.", 13f, Ui.color(this, R.color.md_on_surface_variant)).apply { setPadding(0, dp(12), 0, dp(12)) })
        content.addView(Ui.button(this, "Tambah PDF", outlined = true).apply { setOnClickListener { chooseSource() } }, LinearLayout.LayoutParams(-1, -2))
        list = Ui.column(this)
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(Ui.button(this, "Gabung dan simpan").apply { setOnClickListener { chooseOutput() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setScreen(root)
    }

    private fun render() {
        list.removeAllViews()
        if (sources.isEmpty()) list.addView(Ui.text(this, "Belum ada PDF dipilih.", 14f, Ui.color(this, R.color.md_on_surface_variant)))
        sources.forEachIndexed { index, uri ->
            val dp = { n: Int -> Ui.dp(this, n) }
            val entry = Ui.column(this).apply { setPadding(0, dp(8), 0, dp(8)) }
            entry.addView(Ui.text(this, "${index + 1}. ${name(uri)}", 15f, Ui.color(this, R.color.md_on_surface), true))
            val controls = Ui.row(this)
            controls.addView(Ui.button(this, "Naik", outlined = true).apply {
                isEnabled = index > 0
                setOnClickListener { java.util.Collections.swap(sources, index, index - 1); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(Ui.button(this, "Turun", outlined = true).apply {
                isEnabled = index < sources.lastIndex
                setOnClickListener { java.util.Collections.swap(sources, index, index + 1); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(Ui.button(this, "Hapus", outlined = true).apply {
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
        if (sources.size >= PdfMerge.MAX_FILES) { error("Maksimal ${PdfMerge.MAX_FILES} file."); return }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { pickSource.launch(picker) } catch (_: ActivityNotFoundException) { error("Pemilih dokumen tidak tersedia.") }
    }

    private fun chooseOutput() {
        if (running) return
        if (sources.size < 2) { error("Tambahkan minimal dua file PDF."); return }
        AlertDialog.Builder(this).setTitle("Simpan hasil gabungan")
            .setMessage("Pilih nama dan lokasi baru, bukan salah satu PDF sumber. Jika memilih file lain yang sudah ada, file tersebut dapat ditimpa. Sumber tidak sengaja terpilih akan diblokir jika dapat dikenali. Penyimpanan yang gagal dapat meninggalkan file hasil tidak lengkap.")
            .setNegativeButton("Batal", null).setPositiveButton("Pilih lokasi") { _, _ ->
                val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "Gabungan.pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                try { pickOutput.launch(picker) } catch (_: ActivityNotFoundException) { error("Pemilih dokumen tidak tersedia.") }
            }.show()
    }

    private fun save(destination: Uri) {
        if (running) return
        val selected = sources.toList()
        if (selected.size < 2) { error("Tambahkan minimal dua file PDF."); return }
        if (destination in selected) { error("Tujuan tidak boleh sama dengan file sumber. Pilih nama baru."); return }
        running = true
        busy(true, "Menggabungkan PDF…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { mergeAndSave(selected, destination) }
                running = false; busy(false)
                AlertDialog.Builder(this@PdfMergeActivity).setTitle("PDF berhasil digabung")
                    .setMessage("Hasil telah disimpan dan diverifikasi. Periksa isi dan tata letak hasil di pembaca PDF.")
                    .setPositiveButton("Selesai", null).show()
            } catch (e: Exception) {
                running = false; busy(false)
                error("PDF tidak dapat digabung atau disimpan. File tujuan mungkin belum lengkap; pilih nama baru dan coba lagi.", e)
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
                (contentResolver.openInputStream(uri) ?: throw IOException("PDF ke-${i + 1} tidak dapat dibuka")).use { input ->
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
