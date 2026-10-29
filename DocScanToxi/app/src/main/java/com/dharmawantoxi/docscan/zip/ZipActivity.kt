package com.dharmawantoxi.docscan.zip

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.pdf.VerifiedPdfOutput
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class ZipActivity : BaseActivity() {
    private val sources = mutableListOf<Uri>()
    private var archive: Uri? = null
    private lateinit var list: LinearLayout
    private lateinit var archiveLabel: android.widget.TextView
    private var running = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data; val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            if (uri in sources) { error(tr("File sudah ada dalam daftar.", "This file is already in the list.")); return@registerForActivityResult }
            if (sources.size >= ZipArchive.MAX_FILES) { error(tr("Maksimal ${ZipArchive.MAX_FILES} file.", "Maximum ${ZipArchive.MAX_FILES} files.")); return@registerForActivityResult }
            persistRead(uri, data.flags)
            sources.add(uri); render()
        }
    }
    private val pickArchive = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data; val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            persistRead(uri, data.flags); archive = uri; render()
        }
    }
    private val createZip = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) saveArchive(uri)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data; val tree = data?.data
        if (result.resultCode == Activity.RESULT_OK && tree != null) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (flags != 0) runCatching { contentResolver.takePersistableUriPermission(tree, flags) }
            extract(tree)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList("sources")?.forEach { sources.add(Uri.parse(it)) }
        archive = savedInstanceState?.getString("archive")?.let(Uri::parse)
        buildScreen(); render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("sources", ArrayList(sources.map { it.toString() }))
        archive?.let { outState.putString("archive", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun persistRead(uri: Uri, flags: Int) {
        val granted = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (granted != 0) runCatching { contentResolver.takePersistableUriPermission(uri, granted) }
    }

    private fun buildScreen() {
        val dp = { n: Int -> Ui.dp(this, n) }
        val root = Ui.column(this).apply { setBackgroundColor(Ui.color(context, R.color.md_background)) }
        val bar = Ui.row(this)
        bar.addView(Ui.iconButton(this, R.drawable.ic_close, tr("Tutup ZIP", "Close ZIP")).apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(Ui.text(this, "ZIP", 19f, Ui.color(this, R.color.md_on_surface), true))
        root.addView(bar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = Ui.column(this).apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val muted = Ui.color(this, R.color.md_on_surface_variant)
        content.addView(Ui.text(this, tr("Buat ZIP", "Create ZIP"), 17f, Ui.color(this, R.color.md_on_surface), true))
        content.addView(Ui.text(this, tr("Tambahkan 1–100 file, total maksimal 100 MB. Nama file dalam ZIP diberi nomor sesuai urutan pilihan.", "Add 1–100 files, up to 100 MB in total. Names in the ZIP are numbered in selection order."), 13f, muted).apply { setPadding(0, dp(8), 0, dp(10)) })
        content.addView(Ui.button(this, tr("Tambah file", "Add file"), outlined = true).apply { setOnClickListener { chooseFile(false) } }, LinearLayout.LayoutParams(-1, -2))
        list = Ui.column(this); content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(Ui.button(this, tr("Buat dan simpan ZIP", "Create and save ZIP")).apply { setOnClickListener { chooseZipOutput() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(Ui.text(this, tr("Ekstrak ZIP", "Extract ZIP"), 17f, Ui.color(this, R.color.md_on_surface), true).apply { setPadding(0, dp(24), 0, 0) })
        content.addView(Ui.text(this, tr("Isi ZIP diperiksa lalu disimpan dalam subfolder baru di folder yang Anda pilih. ZIP terenkripsi atau rusak tidak didukung. Maksimal 110 MB arsip, 200 entri, dan 200 MB setelah diekstrak.", "ZIP contents are checked, then saved in a new subfolder of your chosen folder. Encrypted or damaged ZIPs are not supported. Maximum: 110 MB archive, 200 entries, and 200 MB extracted."), 13f, muted).apply { setPadding(0, dp(8), 0, dp(10)) })
        content.addView(Ui.button(this, tr("Pilih ZIP", "Choose ZIP"), outlined = true).apply { setOnClickListener { chooseFile(true) } }, LinearLayout.LayoutParams(-1, -2))
        archiveLabel = Ui.text(this, tr("Belum ada ZIP dipilih.", "No ZIP selected yet."), 14f, muted).apply { setPadding(0, dp(10), 0, dp(10)) }
        content.addView(archiveLabel)
        content.addView(Ui.button(this, tr("Ekstrak ke folder", "Extract to folder")).apply { setOnClickListener { chooseFolder() } }, LinearLayout.LayoutParams(-1, -2))
        setScreen(root)
    }

    private fun render() {
        list.removeAllViews()
        if (sources.isEmpty()) list.addView(Ui.text(this, tr("Belum ada file dipilih.", "No files selected yet."), 14f, Ui.color(this, R.color.md_on_surface_variant)))
        sources.forEachIndexed { index, uri ->
            val row = Ui.row(this)
            row.addView(Ui.text(this, "${index + 1}. ${displayName(uri)}", 14f, Ui.color(this, R.color.md_on_surface)), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.button(this, tr("Hapus", "Delete"), outlined = true).apply { setOnClickListener { sources.removeAt(index); render() } })
            list.addView(row)
        }
        archiveLabel.text = archive?.let { "ZIP: ${displayName(it)}" } ?: tr("Belum ada ZIP dipilih.", "No ZIP selected yet.")
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"
    } catch (_: Exception) { "file" }

    private fun chooseFile(zip: Boolean) {
        if (!zip && sources.size >= ZipArchive.MAX_FILES) { error(tr("Maksimal ${ZipArchive.MAX_FILES} file.", "Maximum ${ZipArchive.MAX_FILES} files.")); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { if (zip) pickArchive.launch(intent) else pickFile.launch(intent) }
        catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia.", "No document picker is available.")) }
    }

    private fun chooseZipOutput() {
        if (running) return
        if (sources.isEmpty()) { error(tr("Tambahkan minimal satu file.", "Add at least one file.")); return }
        AlertDialog.Builder(this).setTitle(tr("Simpan ZIP", "Save ZIP"))
            .setMessage(tr("Pilih nama dan lokasi baru, bukan salah satu file sumber. Penyedia dapat menimpa file yang sudah ada. Penulisan yang gagal dapat meninggalkan ZIP yang tidak lengkap.", "Choose a new name and location, not a source file. The provider may overwrite an existing file. A failed write may leave an incomplete ZIP."))
            .setNegativeButton(tr("Batal", "Cancel"), null).setPositiveButton(tr("Pilih lokasi", "Choose location")) { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, tr("Arsip.zip", "Archive.zip"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                try { createZip.launch(intent) } catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia.", "No document picker is available.")) }
            }.show()
    }

    private fun chooseFolder() {
        if (running) return
        if (archive == null) { error(tr("Pilih ZIP terlebih dahulu.", "Choose a ZIP first.")); return }
        AlertDialog.Builder(this).setTitle(tr("Ekstrak ZIP", "Extract ZIP"))
            .setMessage(tr("Pilih folder tujuan. Aplikasi akan membuat subfolder baru untuk hasil. Jika penyimpanan gagal, subfolder mungkin hanya terisi sebagian. Pastikan ruang penyimpanan cukup.", "Choose a destination folder. The app will create a new subfolder for the result. If saving fails, the subfolder may be incomplete. Make sure there is enough storage."))
            .setNegativeButton(tr("Batal", "Cancel"), null).setPositiveButton(tr("Pilih folder", "Choose folder")) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try { pickFolder.launch(intent) } catch (_: ActivityNotFoundException) { error(tr("Pemilih folder tidak tersedia.", "No folder picker is available.")) }
            }.show()
    }

    private fun saveArchive(destination: Uri) {
        val selected = sources.toList()
        if (running) return
        if (selected.isEmpty()) { error(tr("Tambahkan minimal satu file.", "Add at least one file.")); return }
        if (destination in selected) { error(tr("Tujuan tidak boleh sama dengan sumber.", "The destination must not be a source file.")); return }
        runWork(tr("Membuat ZIP…", "Creating ZIP…"), tr("ZIP berhasil dibuat dan diverifikasi.", "ZIP created and verified successfully.")) {
            val dir = File(cacheDir, "zip-create-${UUID.randomUUID()}")
            if (!dir.mkdirs()) throw IOException("Folder kerja tidak dapat dibuat")
            try {
                var total = 0L
                val inputs = selected.mapIndexed { i, uri ->
                    val file = File(dir, "input-$i")
                    total += ZipDocuments.copyLimited(applicationContext, uri, file, ZipArchive.MAX_INPUT_BYTES - total)
                    ZipArchive.Input(file, displayName(uri))
                }
                val output = File(dir, "result.zip")
                ZipArchive.create(inputs, output)
                val hashes = inputs.map { VerifiedPdfOutput.hashFile(it.file) }
                selected.forEachIndexed { index, uri ->
                    if (VerifiedPdfOutput.hashDestination { contentResolver.openInputStream(uri) } != hashes[index])
                        throw IOException("File sumber berubah selama pembuatan ZIP; ulangi proses")
                }
                val read = { contentResolver.openInputStream(destination) }
                val destinationHash = VerifiedPdfOutput.hashDestination(read)
                selected.indices.filter { hashes[it] == destinationHash }.forEach { index ->
                    // A newly created target and an empty input both hash as empty. Permit
                    // this only when the same provider exposes distinct document IDs.
                    if (inputs[index].file.length() != 0L || !distinctDocuments(selected[index], destination))
                        throw IOException("Tujuan sama dengan atau identik dengan sumber; pilih file baru pada penyedia yang mendukung ID dokumen")
                }
                VerifiedPdfOutput.copyAndVerify(output, VerifiedPdfOutput.hashFile(output), read) { contentResolver.openOutputStream(destination, "wt") }
            } finally { dir.deleteRecursively() }
        }
    }

    private fun distinctDocuments(a: Uri, b: Uri): Boolean {
        if (a.authority != b.authority || a.authority == null) return false
        return runCatching { DocumentsContract.getDocumentId(a) != DocumentsContract.getDocumentId(b) }.getOrDefault(false)
    }

    private fun extract(tree: Uri) {
        val selected = archive ?: run { error(tr("Pilih ZIP terlebih dahulu.", "Choose a ZIP first.")); return }
        if (running) return
        runWork(tr("Mengekstrak ZIP…", "Extracting ZIP…"), tr("Isi ZIP telah disimpan dalam subfolder baru dan diverifikasi.", "The ZIP contents were saved in a new subfolder and verified.")) {
            ZipDocuments.extractToTree(applicationContext, selected, tree)
        }
    }

    private fun runWork(message: String, success: String, work: () -> Unit) {
        running = true; busy(true, message)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                running = false; busy(false)
                AlertDialog.Builder(this@ZipActivity).setTitle(tr("Berhasil", "Success")).setMessage(success).setPositiveButton(tr("Selesai", "Done"), null).show()
            } catch (e: Exception) {
                running = false; busy(false)
                error(tr("Proses ZIP gagal. Hasil atau folder tujuan mungkin belum lengkap; periksa dan coba lagi.", "The ZIP operation failed. The result or destination folder may be incomplete; check it and try again."), e)
            }
        }
    }
}
