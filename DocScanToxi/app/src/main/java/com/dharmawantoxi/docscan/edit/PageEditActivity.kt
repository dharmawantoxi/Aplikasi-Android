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
        bar.addView(Ui.iconButton(c, R.drawable.ic_close, "Tutup editor").apply { setOnClickListener { closeEditor() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(Ui.text(c, "Edit halaman", 18f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        saveAction = Ui.text(c, "Simpan", 15f, Ui.color(c, R.color.md_primary), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            isEnabled = false
            alpha = 0.4f
            contentDescription = "Simpan perubahan halaman"
            setOnClickListener { save() }
        }
        bar.addView(saveAction, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar)
        root.addView(Ui.text(c, "Geser empat sudut untuk memilih area dokumen. Hasil akan diluruskan saat disimpan.", 13f, muted).apply {
            setPadding(dp(22), dp(3), dp(22), dp(12))
        })
        cropView = CropView(c).apply {
            setBackgroundColor(0xFF252C35.toInt())
            onChanged = { dirty = true }
        }
        root.addView(cropView, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = Ui.column(c).apply { setPadding(dp(20), dp(14), dp(20), dp(16)) }
        val tools = Ui.row(c)
        tools.addView(Ui.button(c, "Putar 90°", R.drawable.ic_rotate, outlined = true).apply {
            setOnClickListener { rotate() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        tools.addView(Ui.button(c, "Reset sudut", R.drawable.ic_undo, outlined = true).apply {
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
        val labels = listOf("Asli", "Abu-abu", "Hitam putih")
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
        busy(true, "Memuat halaman…")
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val doc = DocumentStore.get(applicationContext, docId)
                        ?: throw IllegalStateException("Dokumen tidak ditemukan")
                    require(page in doc.pages) { "Halaman tidak ditemukan" }
                    BitmapFactory.decodeFile(DocumentStore.pageFile(applicationContext, docId, page).absolutePath)
                        ?: throw IllegalStateException("Gambar tidak dapat dibaca")
                }
                busy(false)
                cropView.setImage(bitmap)
                saveAction.isEnabled = true
                saveAction.alpha = 1f
            } catch (e: Exception) {
                busy(false)
                AlertDialog.Builder(this@PageEditActivity).setTitle("Halaman tidak dapat dibuka")
                    .setMessage(e.localizedMessage ?: "Silakan coba lagi.")
                    .setPositiveButton("Kembali") { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
        }
    }

    private fun rotate() {
        val source = cropView.bitmap ?: return
        busy(true, "Memutar halaman…")
        lifecycleScope.launch {
            try {
                val rotated = withContext(Dispatchers.Default) { ImageEditor.rotate(source) }
                cropView.setImage(rotated)
                dirty = true
                busy(false)
            } catch (e: Exception) { busy(false); error("Halaman tidak dapat diputar.", e) }
        }
    }

    private fun save() {
        val source = cropView.bitmap ?: return
        val points = cropView.selection()
        val selectedFilter = filterMode
        busy(true, "Menyimpan perubahan…")
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
            } catch (e: Exception) { busy(false); error("Perubahan tidak dapat disimpan.", e) }
        }
    }

    private fun closeEditor() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setTitle("Buang perubahan?")
            .setMessage("Perubahan halaman belum disimpan.")
            .setNegativeButton("Lanjut mengedit", null)
            .setPositiveButton("Buang") { _, _ -> finish() }.show()
    }
}
