package com.dharmawantoxi.docscan.office

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.data.DocumentNames
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Editor segmen DOCX, sel XLSX, dan teks slide PPTX; tidak meraster file Office. */
class OfficeActivity : BaseActivity() {
    companion object { const val EXTRA_CAN_WRITE = "can_write_original" }
    private var session: OfficeSession? = null
    private var sections: List<OfficeSection> = emptyList()
    private var currentSection = 0
    private var sectionVersion = 0
    private var canWriteOriginal = false
    private var loading = false
    private var items: List<OfficeItem> = emptyList()
    private var query = ""
    private var limit = 100
    private var pendingOutput: android.net.Uri? = null
    private var pendingBackup = false

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var sectionLabel: TextView
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private lateinit var search: EditText
    private lateinit var itemList: LinearLayout
    private lateinit var saveCopy: MaterialButton
    private lateinit var overwrite: MaterialButton

    private val copyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { receiveDestination(it, false) }
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { receiveDestination(it, true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data ?: run { finish(); return }
        canWriteOriginal = intent.getBooleanExtra(EXTRA_CAN_WRITE, false)
        currentSection = savedInstanceState?.getInt("section", 0) ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        pendingOutput = savedInstanceState?.getString("pending_output")?.let(android.net.Uri::parse)
        pendingBackup = savedInstanceState?.getBoolean("pending_backup", false) ?: false
        val restoredId = savedInstanceState?.getString("session_id")
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        loading = true
        busy(true, "Membuka file Office…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val opened = if (restoredId == null) OfficeFiles.open(applicationContext, source)
                    else OfficeFiles.restore(applicationContext, restoredId)
                        ?: throw IOException("Sesi Office tidak tersedia. Buka ulang file.")
                    opened to OfficePackage.inspect(opened.working).sections
                }
                loading = false
                busy(false)
                session = result.first
                sections = result.second
                currentSection = currentSection.coerceIn(0, sections.lastIndex)
                updateControls()
                loadSection()
            } catch (e: Exception) {
                loading = false
                busy(false)
                if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@OfficeActivity)
                    .setTitle("File Office tidak dapat dibuka")
                    .setMessage("Gunakan DOCX, XLSX, atau PPTX yang valid dan tidak dilindungi, bertanda tangan, atau berisi makro.\n\n${e.localizedMessage.orEmpty()}")
                    .setPositiveButton("Kembali") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        session?.let { outState.putString("session_id", it.id) }
        outState.putInt("section", currentSection)
        outState.putString("query", query)
        pendingOutput?.let { outState.putString("pending_output", it.toString()) }
        outState.putBoolean("pending_backup", pendingBackup)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        sectionVersion++
        if (isFinishing && !loading) session?.let { OfficeFiles.discard(it) }
        super.onDestroy()
    }

    private fun buildScreen() {
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val onSurface = Ui.color(c, R.color.md_on_surface)
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val primary = Ui.color(c, R.color.md_primary)
        val root = Ui.column(c).apply { setBackgroundColor(Ui.color(c, R.color.md_background)) }
        val bar = Ui.row(c).apply { setPadding(dp(8), dp(6), dp(12), dp(6)) }
        bar.addView(Ui.iconButton(c, R.drawable.ic_close, "Tutup editor Office").apply { setOnClickListener { closeEditor() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = Ui.column(c)
        heading.addView(Ui.text(c, "EDIT FILE OFFICE", 10f, muted, true).apply { letterSpacing = 0.06f })
        title = Ui.text(c, "Memuat file…", 17f, onSurface, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        heading.addView(title)
        bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val scroll = ScrollView(c).apply { isFillViewport = true }
        val content = Ui.column(c).apply { setPadding(dp(20), dp(6), dp(20), dp(20)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = Ui.text(c, "File asli belum diubah.", 13f, muted).apply { setPadding(0, 0, 0, dp(10)) }
        content.addView(status)
        val pagination = Ui.row(c)
        previous = Ui.button(c, "Sebelumnya", outlined = true).apply { setOnClickListener { changeSection(-1) } }
        next = Ui.button(c, "Berikutnya", outlined = true).apply { setOnClickListener { changeSection(1) } }
        sectionLabel = Ui.text(c, "–", 13f, muted, true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        pagination.addView(previous, LinearLayout.LayoutParams(0, dp(48), 1f))
        pagination.addView(sectionLabel, LinearLayout.LayoutParams(dp(105), -2))
        pagination.addView(next, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(pagination)
        content.addView(Ui.text(c, "Ketuk teks atau sel untuk menggantinya. Tampilan ini berupa daftar, bukan pratinjau tata letak. Periksa hasil di aplikasi Office.", 13f, muted).apply {
            setPadding(0, dp(14), 0, dp(10))
        })
        search = EditText(c).apply {
            hint = "Cari teks atau alamat sel"
            contentDescription = "Cari isi Office"
            setSingleLine(true)
            setTextColor(onSurface); setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.bg(Ui.color(c, R.color.md_surface_container_lowest), dp(12).toFloat(),
                Ui.color(c, R.color.md_outline_variant), dp(1))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty(); limit = 100
                    if (::itemList.isInitialized) renderItems()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        itemList = Ui.column(c).apply { setPadding(0, dp(10), 0, 0) }
        content.addView(itemList)
        search.setText(query)
        val actions = Ui.row(c).apply { setPadding(dp(20), dp(8), dp(20), dp(12)) }
        saveCopy = Ui.button(c, "Simpan salinan", outlined = true).apply { setOnClickListener { requestCopy() } }
        overwrite = Ui.button(c, "Ganti file asli").apply { setOnClickListener { requestOverwrite() } }
        actions.addView(saveCopy, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(overwrite, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        root.addView(actions)
        setScreen(root)
        updateControls()
    }

    private fun loadSection() {
        val current = session ?: return
        val section = sections.getOrNull(currentSection) ?: return
        val version = ++sectionVersion
        loading = true
        updateControls()
        busy(true, "Memuat ${section.label}…")
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { OfficePackage.readSection(current.working, section) }
                if (version != sectionVersion || isFinishing || isDestroyed) return@launch
                items = loaded; limit = 100; loading = false
                busy(false); updateControls(); renderItems(); deliverPending()
            } catch (e: Exception) {
                if (version != sectionVersion) return@launch
                items = emptyList(); loading = false
                busy(false); updateControls(); renderItems(e.localizedMessage ?: "Bagian ini tidak dapat dibaca")
                deliverPending()
            }
        }
    }

    private fun renderItems(problem: String? = null) {
        if (!::itemList.isInitialized) return
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val primary = Ui.color(c, R.color.md_primary)
        itemList.removeAllViews()
        val matches = items.filter { query.isBlank() || it.text.contains(query, true) || it.key.contains(query, true) }
        if (problem != null) itemList.addView(Ui.text(c, problem, 13f, muted))
        else if (matches.isEmpty()) itemList.addView(Ui.text(c, if (items.isEmpty()) "Tidak ada teks atau sel yang didukung pada bagian ini. Formula dan konten tertentu dilewati." else "Tidak ada isi yang cocok dengan pencarian.", 13f, muted))
        else {
            itemList.addView(Ui.text(c, "${matches.size} isi yang dapat diedit", 12f, muted, true).apply { setPadding(0, 0, 0, dp(8)) })
            matches.take(limit).forEach { item ->
                val row = Ui.row(c).apply {
                    background = Ui.bg(Ui.color(c, R.color.md_surface_container_lowest), dp(12).toFloat(),
                        Ui.color(c, R.color.md_outline_variant), dp(1))
                    setPadding(dp(14), dp(10), dp(12), dp(10)); minimumHeight = dp(52)
                    isClickable = true; isFocusable = true
                    contentDescription = if (session?.format == OfficeFormat.EXCEL) "Edit sel ${item.key}: ${item.text.take(80)}" else "Edit teks Office: ${item.text.take(80)}"
                    setOnClickListener { editItem(item) }
                }
                val caption = if (session?.format == OfficeFormat.EXCEL) "${item.key} · ${item.text}" else item.text
                row.addView(Ui.text(c, caption, 14f, Ui.color(c, R.color.md_on_surface)).apply { maxLines = 3 }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Ui.image(c, R.drawable.ic_edit, primary), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) })
                itemList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            if (matches.size > limit) itemList.addView(Ui.button(c, "Tampilkan lebih banyak", outlined = true).apply {
                setOnClickListener { limit += 100; renderItems() }
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
    }

    private fun editItem(item: OfficeItem) {
        if (loading) return
        val input = EditText(this).apply {
            setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(32767))
            setText(item.text); setSelection(text.length); contentDescription = "Isi Office pengganti"
        }
        val frame = Ui.column(this).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0); addView(input) }
        val detail = when (item.type) {
            OfficeItemType.NUMBER -> "Masukkan angka dengan titik desimal, misalnya 123.45. Nilai tanggal dapat berupa angka seri Excel."
            OfficeItemType.BOOLEAN -> "Masukkan TRUE atau FALSE."
            OfficeItemType.TEXT -> "Format run dan bagian lain dipertahankan sejauh didukung. Teks yang lebih panjang dapat mengubah tata letak."
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (session?.format == OfficeFormat.EXCEL) "Ganti sel ${item.key}" else "Ganti teks")
            .setMessage(detail).setView(frame).setNegativeButton("Batal", null).setPositiveButton("Terapkan", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value == item.text) { dialog.dismiss(); return@setOnClickListener }
                dialog.dismiss(); applyReplacement(item, value)
            }
        }
        dialog.show()
    }

    private fun applyReplacement(item: OfficeItem, replacement: String) {
        val current = session ?: return
        val section = sections.getOrNull(currentSection) ?: return
        loading = true; updateControls(); busy(true, "Menyimpan perubahan Office…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { OfficeFiles.replace(current, section, item, replacement) }
                loading = false; busy(false); updateControls(); loadSection()
            } catch (e: Exception) {
                loading = false; busy(false); updateControls()
                if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@OfficeActivity)
                    .setTitle("Isi tidak dapat diganti")
                    .setMessage("File asli belum ditulis. Muat ulang bagian ini dan periksa hasilnya.\n\n${e.localizedMessage.orEmpty()}")
                    .setPositiveButton("Muat ulang") { _, _ -> loadSection() }.setOnCancelListener { loadSection() }.show()
            }
        }
    }

    private fun changeSection(offset: Int) {
        val target = currentSection + offset
        if (!loading && target in sections.indices) { currentSection = target; loadSection() }
    }

    private fun updateControls() {
        if (!::title.isInitialized) return
        val current = session
        title.text = current?.let { "${it.title} · ${it.format.displayName}" } ?: "Memuat file…"
        status.text = when {
            current == null -> "File asli belum diubah."
            current.sourceUncertain -> "Kondisi file asli tidak pasti; periksa cadangan dan buka ulang."
            current.changed && current.unsaved -> "Ada perubahan; file asli belum diubah."
            current.changed -> "Salinan sudah disimpan; file asli belum diubah."
            else -> "File kerja sama dengan file asli."
        }
        sectionLabel.text = sections.getOrNull(currentSection)?.let { "${it.label}\n${currentSection + 1} / ${sections.size}" } ?: "–"
        previous.isEnabled = current != null && !loading && currentSection > 0
        next.isEnabled = current != null && !loading && currentSection < sections.lastIndex
        saveCopy.isEnabled = current != null && !loading
        overwrite.isEnabled = current != null && current.changed && !current.sourceUncertain && !loading
    }

    private fun requestCopy() {
        val current = session ?: return
        AlertDialog.Builder(this).setTitle("Simpan sebagai salinan")
            .setMessage("Pilih nama dan lokasi baru, bukan file asli atau cadangan penting. Beberapa penyedia dapat menimpa file saat dipilih, sebelum aplikasi dapat memeriksanya.")
            .setNegativeButton("Batal", null).setPositiveButton("Pilih lokasi") { _, _ ->
                launchCreate(false, "${DocumentNames.fileName(current.title).take(68)} (edit).${current.format.extension}")
            }.show()
    }

    private fun requestOverwrite() {
        val current = session ?: return
        if (!canWriteOriginal) { error("Penyedia tidak memberi izin menulis file asli. Gunakan Simpan salinan."); return }
        AlertDialog.Builder(this).setTitle("Ganti file Office asli?")
            .setMessage("Simpan cadangan terpisah terlebih dahulu. Pilih nama dan lokasi baru, bukan file asli atau cadangan penting: beberapa penyedia dapat menimpa file saat dipilih.")
            .setNegativeButton("Batal", null).setPositiveButton("Buat cadangan") { _, _ ->
                launchCreate(true, "${DocumentNames.fileName(current.title).take(64)} (cadangan).${current.format.extension}")
            }.show()
    }

    private fun launchCreate(backup: Boolean, fileName: String) {
        val current = session ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = current.format.mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        try { if (backup) backupLauncher.launch(intent) else copyLauncher.launch(intent) }
        catch (_: ActivityNotFoundException) { error("Pemilih lokasi penyimpanan tidak tersedia.") }
    }

    private fun deliverPending() {
        if (loading || session == null) return
        val uri = pendingOutput ?: return
        pendingOutput = null
        receiveDestination(uri, pendingBackup)
    }

    private fun receiveDestination(uri: android.net.Uri, backup: Boolean) {
        if (session == null || loading) { pendingOutput = uri; pendingBackup = backup; return }
        val current = session ?: return
        loading = true; updateControls(); busy(true, if (backup) "Menyimpan cadangan…" else "Menyimpan salinan Office…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (backup) OfficeFiles.saveBackup(applicationContext, current, uri) else OfficeFiles.saveCopy(applicationContext, current, uri)
                }
                loading = false; busy(false); updateControls()
                if (backup) confirmOverwrite(current) else toast("Salinan ${current.format.displayName} tersimpan.")
            } catch (e: Exception) {
                loading = false; busy(false); updateControls()
                error("File tidak dapat disimpan. Periksa file asli jika tadi dipilih di dialog.", e)
            }
        }
    }

    private fun confirmOverwrite(current: OfficeSession) {
        AlertDialog.Builder(this).setTitle("Cadangan tersimpan")
            .setMessage("Ganti file asli dengan hasil edit sekarang? Pastikan cadangan benar-benar terpisah.")
            .setNegativeButton("Batal") { _, _ -> current.backupUri = null }
            .setPositiveButton("Ganti file asli") { _, _ -> overwrite(current) }
            .setOnCancelListener { current.backupUri = null }.show()
    }

    private fun overwrite(current: OfficeSession) {
        loading = true; updateControls(); busy(true, "Mengganti file Office asli…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { OfficeFiles.overwriteOriginal(applicationContext, current) }
                loading = false; busy(false); updateControls(); setResult(Activity.RESULT_OK); toast("File asli berhasil diganti.")
            } catch (e: Exception) {
                loading = false; busy(false); updateControls()
                if (e is CommittedOfficeSessionException) AlertDialog.Builder(this@OfficeActivity)
                    .setTitle("File asli sudah diganti").setMessage(e.message).setCancelable(false)
                    .setPositiveButton("Tutup") { _, _ -> finish() }.show()
                else error("Penggantian file belum dapat diselesaikan.", e)
            }
        }
    }

    private fun closeEditor() {
        if (loading) return
        if (session?.unsaved != true) { finish(); return }
        AlertDialog.Builder(this).setTitle("Buang perubahan?")
            .setMessage("Perubahan belum disimpan ke salinan atau file asli.")
            .setNegativeButton("Lanjut mengedit", null).setPositiveButton("Buang") { _, _ -> finish() }.show()
    }
}
