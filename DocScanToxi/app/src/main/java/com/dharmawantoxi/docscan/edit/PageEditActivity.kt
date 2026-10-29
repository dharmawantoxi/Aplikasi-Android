package com.dharmawantoxi.docscan.edit

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.DocumentActivity
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PageEditActivity : BaseActivity() {
    companion object { const val EXTRA_PAGE = "page_id" }
    private lateinit var docId: String
    private lateinit var page: String
    private lateinit var cropView: CropView
    private lateinit var filterButtons: LinearLayout
    private lateinit var saveAction: TextView
    private var filterMode = 0
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        docId = intent.getStringExtra(DocumentActivity.EXTRA_ID).orEmpty()
        page = intent.getStringExtra(EXTRA_PAGE).orEmpty()
        if (docId.isBlank() || page.isBlank()) { finish(); return }
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        loadImage()
    }

    private fun buildScreen() {
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val onSurface = Ui.color(c, R.color.md_on_surface)
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val root = Ui.column(c).apply { setBackgroundColor(Ui.color(c, R.color.md_background)) }
        val bar = Ui.row(c).apply { setPadding(dp(8), dp(6), dp(12), dp(6)) }
        bar.addView(Ui.iconButton(c, R.drawable.ic_close, tr("Tutup editor", "Close editor")).apply { setOnClickListener { closeEditor() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(Ui.text(c, tr("Edit halaman", "Edit page"), 18f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        saveAction = Ui.text(c, tr("Simpan", "Save"), 15f, Ui.color(c, R.color.md_primary), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            isEnabled = false
            alpha = 0.4f
            contentDescription = tr("Simpan perubahan halaman", "Save page changes")
            setOnClickListener { save() }
        }
        bar.addView(saveAction, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar)
        root.addView(Ui.text(c, tr("Geser empat sudut untuk memilih area dokumen. Hasil akan diluruskan saat disimpan.", "Drag the four corners to select the document area. It will be straightened when saved."), 13f, muted).apply {
            setPadding(dp(22), dp(3), dp(22), dp(12))
        })
        cropView = CropView(c).apply {
            setBackgroundColor(0xFF252C35.toInt())
            onChanged = { dirty = true }
        }
        root.addView(cropView, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = Ui.column(c).apply { setPadding(dp(20), dp(14), dp(20), dp(16)) }
        val tools = Ui.row(c)
        tools.addView(Ui.button(c, tr("Putar 90°", "Rotate 90°"), R.drawable.ic_rotate, outlined = true).apply {
            setOnClickListener { rotate() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        tools.addView(Ui.button(c, tr("Reset sudut", "Reset corners"), R.drawable.ic_undo, outlined = true).apply {
            setOnClickListener { cropView.resetCorners(); dirty = true }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        controls.addView(tools)
        controls.addView(Ui.text(c, "FILTER", 10f, muted, true).apply {
            letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(6))
        })
        filterButtons = Ui.row(c)
        controls.addView(filterButtons)
        root.addView(controls)
        setScreen(root)
        renderFilters()
    }

    private fun renderFilters() {
        val labels = listOf(tr("Asli", "Original"), tr("Abu-abu", "Grayscale"), tr("Hitam putih", "Black and white"))
        filterButtons.removeAllViews()
        labels.forEachIndexed { index, label ->
            val button = Ui.button(this, label, outlined = filterMode != index).apply {
                setTextSize(12f)
                setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
                setOnClickListener {
                    if (filterMode != index) dirty = true
                    filterMode = index
                    cropView.filterMode = index
                    renderFilters()
                }
            }
            filterButtons.addView(button, LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f).apply {
                if (index > 0) leftMargin = Ui.dp(this@PageEditActivity, 6)
            })
        }
    }

    private fun loadImage() {
        busy(true, tr("Memuat halaman…", "Loading page…"))
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val doc = DocumentStore.get(applicationContext, docId)
                        ?: throw IllegalStateException(tr("Dokumen tidak ditemukan", "Document not found"))
                    require(page in doc.pages) { tr("Halaman tidak ditemukan", "Page not found") }
                    BitmapFactory.decodeFile(DocumentStore.pageFile(applicationContext, docId, page).absolutePath)
                        ?: throw IllegalStateException(tr("Gambar tidak dapat dibaca", "The image could not be read"))
                }
                busy(false)
                cropView.setImage(bitmap)
                saveAction.isEnabled = true
                saveAction.alpha = 1f
            } catch (e: Exception) {
                busy(false)
                AlertDialog.Builder(this@PageEditActivity).setTitle(tr("Halaman tidak dapat dibuka", "The page could not be opened"))
                    .setMessage(detail(e.localizedMessage) ?: tr("Silakan coba lagi.", "Please try again."))
                    .setPositiveButton(tr("Kembali", "Back")) { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
        }
    }

    private fun rotate() {
        val source = cropView.bitmap ?: return
        busy(true, tr("Memutar halaman…", "Rotating page…"))
        lifecycleScope.launch {
            try {
                val rotated = withContext(Dispatchers.Default) { ImageEditor.rotate(source) }
                cropView.setImage(rotated)
                dirty = true
                busy(false)
            } catch (e: Exception) { busy(false); error(tr("Halaman tidak dapat diputar.", "The page could not be rotated."), e) }
        }
    }

    private fun save() {
        val source = cropView.bitmap ?: return
        val points = cropView.selection()
        val selectedFilter = filterMode
        busy(true, tr("Menyimpan perubahan…", "Saving changes…"))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val result = ImageEditor.crop(source, points, selectedFilter)
                    try { DocumentStore.replacePage(applicationContext, docId, page, result) }
                    finally { result.recycle() }
                }
                busy(false)
                dirty = false
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) { busy(false); error(tr("Perubahan tidak dapat disimpan.", "Changes could not be saved."), e) }
        }
    }

    private fun closeEditor() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setTitle(tr("Buang perubahan?", "Discard changes?"))
            .setMessage(tr("Perubahan halaman belum disimpan.", "Page changes have not been saved."))
            .setNegativeButton(tr("Lanjut mengedit", "Continue editing"), null)
            .setPositiveButton(tr("Buang", "Discard")) { _, _ -> finish() }.show()
    }
}
