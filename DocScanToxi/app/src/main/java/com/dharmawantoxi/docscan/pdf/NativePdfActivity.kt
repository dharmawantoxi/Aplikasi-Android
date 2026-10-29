package com.dharmawantoxi.docscan.pdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Editor teks PDF asli. PDF yang dibuka tidak diraster untuk hasil akhirnya. */
class NativePdfActivity : BaseActivity() {
    companion object { const val EXTRA_CAN_WRITE = "can_write_original" }
    private var session: NativePdfSession? = null
    private var canWriteOriginal = false
    private var pageIndex = 0
    private var pageVersion = 0
    private var loading = false
    private var segments: List<PdfTextSegment> = emptyList()
    private var skipped = 0
    private var limit = 100
    private var query = ""
    private var previewBitmap: Bitmap? = null
    private var pendingOutput: Uri? = null
    private var pendingBackup = false

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var pageLabel: TextView
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private lateinit var preview: ImageView
    private lateinit var search: EditText
    private lateinit var textList: LinearLayout
    private lateinit var saveCopy: MaterialButton
    private lateinit var overwrite: MaterialButton

    private val saveCopyLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) receiveDestination(uri, false)
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) receiveDestination(uri, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data
        if (source == null) { finish(); return }
        canWriteOriginal = intent.getBooleanExtra(EXTRA_CAN_WRITE, false)
        pageIndex = savedInstanceState?.getInt("page", 0) ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        val restoredId = savedInstanceState?.getString("session_id")
        pendingOutput = savedInstanceState?.getString("pending_output")?.let(Uri::parse)
        pendingBackup = savedInstanceState?.getBoolean("pending_backup", false) ?: false
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        PDFBoxResourceLoader.init(applicationContext)
        openSession(source, restoredId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        session?.let { outState.putString("session_id", it.id) }
        outState.putInt("page", pageIndex)
        outState.putString("query", query)
        pendingOutput?.let { outState.putString("pending_output", it.toString()) }
        outState.putBoolean("pending_backup", pendingBackup)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ++pageVersion
        if (::preview.isInitialized) preview.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        if (isFinishing && !loading) session?.let { NativePdfFiles.discard(it) }
        super.onDestroy()
    }

    private fun buildScreen() {
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val textColor = Ui.color(c, R.color.md_on_surface)
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val primary = Ui.color(c, R.color.md_primary)
        val root = Ui.column(c).apply { setBackgroundColor(Ui.color(c, R.color.md_background)) }
        val bar = Ui.row(c).apply { setPadding(dp(8), dp(6), dp(12), dp(6)) }
        bar.addView(Ui.iconButton(c, R.drawable.ic_close, "Tutup editor PDF").apply { setOnClickListener { closeEditor() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = Ui.column(c)
        heading.addView(Ui.text(c, "EDIT TEKS PDF ASLI", 10f, muted, true).apply { letterSpacing = 0.06f })
        title = Ui.text(c, "Memuat PDF…", 17f, textColor, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        heading.addView(title)
        bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val scroll = ScrollView(c).apply { isFillViewport = true }
        val content = Ui.column(c).apply { setPadding(dp(20), dp(6), dp(20), dp(20)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = Ui.text(c, "File asli belum diubah.", 13f, muted).apply { setPadding(0, 0, 0, dp(8)) }
        content.addView(status)
        preview = ImageView(c).apply {
            setBackgroundColor(Ui.color(c, R.color.page_background))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Pratinjau halaman PDF asli"
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(260)))
        val pagination = Ui.row(c).apply { setPadding(0, dp(10), 0, dp(8)) }
        previous = Ui.button(c, "Sebelumnya", outlined = true).apply { setOnClickListener { changePage(-1) } }
        next = Ui.button(c, "Berikutnya", outlined = true).apply { setOnClickListener { changePage(1) } }
        pageLabel = Ui.text(c, "– / –", 13f, muted, true).apply { gravity = Gravity.CENTER }
        pagination.addView(previous, LinearLayout.LayoutParams(0, dp(48), 1f))
        pagination.addView(pageLabel, LinearLayout.LayoutParams(dp(74), -2))
        pagination.addView(next, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(pagination)
        content.addView(Ui.text(c, "Ketuk segmen teks untuk menggantinya. Periksa pratinjau: teks tidak mengalir ulang secara otomatis.", 13f, muted).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        search = EditText(c).apply {
            hint = "Cari segmen teks"
            contentDescription = "Cari teks PDF"
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.bg(Ui.color(c, R.color.md_surface_container_lowest), dp(12).toFloat(),
                Ui.color(c, R.color.md_outline_variant), dp(1))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    limit = 100
                    if (::textList.isInitialized) renderSegments()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        textList = Ui.column(c).apply { setPadding(0, dp(10), 0, 0) }
        content.addView(textList)
        search.setText(query)

        val actions = Ui.row(c).apply { setPadding(dp(20), dp(8), dp(20), dp(12)) }
        saveCopy = Ui.button(c, "Simpan salinan", outlined = true).apply {
            setOnClickListener { requestCopy() }
        }
        overwrite = Ui.button(c, "Ganti file asli").apply { setOnClickListener { requestOverwrite() } }
        actions.addView(saveCopy, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(overwrite, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        root.addView(actions)
        setScreen(root)
        updateControls()
    }

    private fun openSession(source: Uri, restoredId: String?) {
        loading = true
        busy(true, "Membuka PDF asli…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (restoredId == null) NativePdfFiles.open(applicationContext, source)
                    else NativePdfFiles.restore(applicationContext, restoredId)
                        ?: throw IOException("Sesi edit tidak tersedia lagi. Buka ulang PDF asli.")
                }
                loading = false
                busy(false)
                session = result
                pageIndex = pageIndex.coerceIn(0, result.pageCount - 1)
                updateControls()
                loadPage()
            } catch (e: Exception) {
                loading = false
                busy(false)
                if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@NativePdfActivity)
                    .setTitle("PDF tidak dapat dibuka")
                    .setMessage("Pastikan PDF valid, tidak dilindungi kata sandi, dan tidak bertanda tangan digital.\n\n${e.localizedMessage.orEmpty()}")
                    .setPositiveButton("Kembali") { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
        }
    }

    private data class LoadedPage(val bitmap: Bitmap, val text: PdfTextPage?, val error: String?)

    private fun loadPage() {
        val current = session ?: return
        val index = pageIndex
        val version = ++pageVersion
        loading = true
        updateControls()
        busy(true, "Memuat halaman…")
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val bitmap = renderPreview(current.working, index)
                    try {
                        val parsed = runCatching { NativePdfText.readPage(current.working, index) }
                        LoadedPage(bitmap, parsed.getOrNull(),
                            parsed.exceptionOrNull()?.localizedMessage ?: if (parsed.isFailure) "Teks halaman tidak dapat dibaca" else null)
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
                if (version != pageVersion || isFinishing || isDestroyed) { loaded.bitmap.recycle(); return@launch }
                preview.setImageBitmap(loaded.bitmap)
                previewBitmap?.recycle()
                previewBitmap = loaded.bitmap
                segments = loaded.text?.segments.orEmpty()
                skipped = loaded.text?.skipped ?: 0
                limit = 100
                loading = false
                busy(false)
                updateControls()
                renderSegments(loaded.error)
                deliverPendingDestination()
            } catch (e: Exception) {
                if (version != pageVersion) return@launch
                loading = false
                busy(false)
                updateControls()
                error("Halaman PDF tidak dapat ditampilkan.", e)
                deliverPendingDestination()
            }
        }
    }

    private fun renderPreview(file: File, page: Int): Bitmap {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(fd) } catch (e: Exception) { runCatching { fd.close() }; throw e }
        renderer.use { pdf ->
            pdf.openPage(page).use { source ->
                val scale = 1600f / max(source.width, source.height)
                val bitmap = Bitmap.createBitmap((source.width * scale).roundToInt().coerceAtLeast(1),
                    (source.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
            }
        }
    }

    private fun renderSegments(problem: String? = null) {
        if (!::textList.isInitialized) return
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val primary = Ui.color(c, R.color.md_primary)
        textList.removeAllViews()
        val filtered = segments.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
        if (problem != null) {
            textList.addView(Ui.text(c, problem, 13f, muted))
        } else if (filtered.isEmpty()) {
            val message = if (segments.isNotEmpty()) "Tidak ada segmen yang cocok dengan pencarian."
                else "Tidak ada teks yang dapat diedit pada halaman ini. Teks pada gambar, formulir, XObject, atau font tertentu mungkin tidak didukung."
            textList.addView(Ui.text(c, message, 13f, muted))
        } else {
            textList.addView(Ui.text(c, "${filtered.size} segmen teks${if (skipped > 0) " · $skipped dilewati" else ""}", 12f, muted, true).apply {
                setPadding(0, 0, 0, dp(8))
            })
            filtered.take(limit).forEach { segment ->
                val row = Ui.row(c).apply {
                    background = Ui.bg(Ui.color(c, R.color.md_surface_container_lowest), dp(12).toFloat(),
                        Ui.color(c, R.color.md_outline_variant), dp(1))
                    setPadding(dp(14), dp(10), dp(12), dp(10))
                    minimumHeight = dp(52)
                    isClickable = true; isFocusable = true
                    contentDescription = "Edit teks PDF: ${segment.text.take(100)}"
                    setOnClickListener { editSegment(segment) }
                }
                row.addView(Ui.text(c, segment.text, 14f, Ui.color(c, R.color.md_on_surface)).apply { maxLines = 3 },
                    LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Ui.image(c, R.drawable.ic_edit, primary), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) })
                textList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            if (filtered.size > limit) textList.addView(Ui.button(c, "Tampilkan lebih banyak", outlined = true).apply {
                setOnClickListener { limit += 100; renderSegments() }
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
    }

    private fun editSegment(segment: PdfTextSegment) {
        if (loading) return
        val input = EditText(this).apply {
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(2000))
            setText(segment.text)
            setSelection(text.length)
            contentDescription = "Teks PDF pengganti"
        }
        val frame = Ui.column(this).apply {
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Ganti teks asli")
            .setMessage("Gunakan karakter yang tersedia pada font PDF. Tata letak dapat bergeser atau bertumpuk; periksa pratinjau. Metadata dan teks aksesibilitas mungkin tidak ikut berubah.")
            .setView(frame).setNegativeButton("Batal", null).setPositiveButton("Terapkan", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val replacement = input.text.toString()
                if (replacement == segment.text) { dialog.dismiss(); return@setOnClickListener }
                dialog.dismiss()
                applyReplacement(segment, replacement)
            }
        }
        dialog.show()
    }

    private fun applyReplacement(segment: PdfTextSegment, replacement: String) {
        val current = session ?: return
        loading = true
        updateControls()
        busy(true, "Mengganti teks PDF…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { NativePdfFiles.replaceText(current, pageIndex, segment, replacement) }
                loading = false
                busy(false)
                updateControls()
                loadPage()
            } catch (e: Exception) {
                loading = false
                busy(false)
                updateControls()
                if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@NativePdfActivity)
                    .setTitle("Teks tidak dapat diganti")
                    .setMessage("File asli belum ditulis. Muat ulang halaman dan periksa hasilnya.\n\n${e.localizedMessage.orEmpty()}")
                    .setPositiveButton("Muat ulang") { _, _ -> loadPage() }
                    .setOnCancelListener { loadPage() }.show()
            }
        }
    }

    private fun changePage(offset: Int) {
        val current = session ?: return
        val target = pageIndex + offset
        if (loading || target !in 0 until current.pageCount) return
        pageIndex = target
        loadPage()
    }

    private fun updateControls() {
        if (!::title.isInitialized) return
        val current = session
        title.text = current?.title ?: "Memuat PDF…"
        status.text = when {
            current == null -> "File asli belum diubah."
            current.sourceUncertain -> "Kondisi file asli tidak pasti; periksa cadangan dan buka ulang PDF."
            current.changed && current.unsaved -> "Ada perubahan; file asli belum diubah."
            current.changed -> "Salinan sudah disimpan; file asli belum diubah."
            else -> "File kerja sama dengan PDF asli."
        }
        pageLabel.text = if (current == null) "– / –" else "${pageIndex + 1} / ${current.pageCount}"
        previous.isEnabled = current != null && !loading && pageIndex > 0
        next.isEnabled = current != null && !loading && pageIndex < current.pageCount - 1
        saveCopy.isEnabled = current != null && !loading
        overwrite.isEnabled = current != null && current.changed && !current.sourceUncertain && !loading
    }

    private fun requestCopy() {
        val current = session ?: return
        AlertDialog.Builder(this).setTitle("Simpan sebagai salinan")
            .setMessage("Pilih nama dan lokasi baru, bukan file asli atau cadangan penting. Beberapa penyedia dapat menimpa file saat dipilih, sebelum aplikasi dapat memeriksanya.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Pilih lokasi") { _, _ ->
                try { saveCopyLauncher.launch("${DocumentNames.fileName(current.title).take(68)} (edit).pdf") }
                catch (_: ActivityNotFoundException) { error("Pemilih lokasi penyimpanan tidak tersedia.") }
            }.show()
    }

    private fun requestOverwrite() {
        val current = session ?: return
        if (!canWriteOriginal) {
            error("Penyedia file tidak memberikan izin menulis ke PDF asli. Gunakan Simpan salinan atau pilih file yang dapat ditulis.")
            return
        }
        AlertDialog.Builder(this).setTitle("Ganti file PDF asli?")
            .setMessage("File asli akan ditimpa. Simpan cadangan terpisah terlebih dahulu. Pilih nama dan lokasi baru, bukan file asli atau cadangan penting: beberapa penyedia dapat menimpa file saat dipilih, sebelum aplikasi memeriksanya.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat cadangan") { _, _ ->
                try { backupLauncher.launch("${DocumentNames.fileName(current.title).take(64)} (cadangan).pdf") }
                catch (_: ActivityNotFoundException) { error("Pemilih lokasi penyimpanan tidak tersedia.") }
            }.show()
    }

    private fun deliverPendingDestination() {
        if (loading || session == null) return
        val uri = pendingOutput ?: return
        pendingOutput = null
        receiveDestination(uri, pendingBackup)
    }

    private fun receiveDestination(uri: Uri, backup: Boolean) {
        if (session == null || loading) {
            pendingOutput = uri
            pendingBackup = backup
            return
        }
        val current = session ?: return
        loading = true
        updateControls()
        busy(true, if (backup) "Menyimpan cadangan…" else "Menyimpan salinan PDF…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (backup) NativePdfFiles.saveBackup(applicationContext, current, uri)
                    else NativePdfFiles.saveCopy(applicationContext, current, uri)
                }
                loading = false
                busy(false)
                updateControls()
                if (backup) confirmOverwrite(current) else toast("Salinan PDF berhasil disimpan.")
            } catch (e: Exception) {
                loading = false
                busy(false)
                updateControls()
                error(if (backup) "Cadangan tidak dapat disimpan. Periksa file asli dan pilih lokasi baru." else "Salinan PDF tidak dapat disimpan. Periksa file asli jika tadi dipilih di dialog.", e)
            }
        }
    }

    private fun confirmOverwrite(current: NativePdfSession) {
        AlertDialog.Builder(this).setTitle("Cadangan tersimpan")
            .setMessage("Ganti file asli dengan hasil edit sekarang? Pastikan cadangan berada di file terpisah.")
            .setNegativeButton("Batal") { _, _ -> current.backupReady = false; current.backupUri = null }
            .setPositiveButton("Ganti file asli") { _, _ -> overwriteOriginal(current) }
            .setOnCancelListener { current.backupReady = false; current.backupUri = null }.show()
    }

    private fun overwriteOriginal(current: NativePdfSession) {
        loading = true
        updateControls()
        busy(true, "Mengganti PDF asli…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { NativePdfFiles.overwriteOriginal(applicationContext, current) }
                loading = false
                busy(false)
                updateControls()
                setResult(Activity.RESULT_OK)
                toast("File PDF asli berhasil diganti.")
            } catch (e: Exception) {
                loading = false
                busy(false)
                updateControls()
                if (e is CommittedPdfSessionException) {
                    AlertDialog.Builder(this@NativePdfActivity).setTitle("File asli sudah diganti")
                        .setMessage(e.message).setCancelable(false)
                        .setPositiveButton("Tutup") { _, _ -> finish() }.show()
                } else error("Penggantian PDF belum dapat diselesaikan.", e)
            }
        }
    }

    private fun closeEditor() {
        if (loading) return
        if (session?.unsaved != true) { finish(); return }
        AlertDialog.Builder(this).setTitle("Buang perubahan?")
            .setMessage("Perubahan belum disimpan ke salinan atau file asli.")
            .setNegativeButton("Lanjut mengedit", null)
            .setPositiveButton("Buang") { _, _ -> finish() }.show()
    }
}
