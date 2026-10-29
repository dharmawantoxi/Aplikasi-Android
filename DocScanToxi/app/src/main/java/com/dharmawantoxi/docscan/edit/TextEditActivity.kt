package com.dharmawantoxi.docscan.edit

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.DocumentActivity
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Menambahkan satu baris teks ke salinan gambar halaman. */
class TextEditActivity : BaseActivity() {
    private lateinit var docId: String
    private lateinit var page: String
    private lateinit var preview: TextOverlayView
    private lateinit var input: EditText
    private lateinit var sizeControl: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var saveAction: TextView
    private lateinit var colorButtons: List<MaterialButton>
    private val colors = listOf(Color.BLACK, 0xFF0B5FBF.toInt(), 0xFFBA1A1A.toInt())
    private var colorIndex = 0
    private var positionX = 0.5f
    private var positionY = 0.5f
    private var dirty = false
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        docId = intent.getStringExtra(DocumentActivity.EXTRA_ID).orEmpty()
        page = intent.getStringExtra(PageEditActivity.EXTRA_PAGE).orEmpty()
        if (docId.isBlank() || page.isBlank()) { finish(); return }
        positionX = savedInstanceState?.getFloat("x", 0.5f) ?: 0.5f
        positionY = savedInstanceState?.getFloat("y", 0.5f) ?: 0.5f
        colorIndex = (savedInstanceState?.getInt("color", 0) ?: 0).coerceIn(colors.indices)
        buildScreen()
        input.setText(savedInstanceState?.getString("text").orEmpty())
        sizeControl.progress = savedInstanceState?.getInt("size", 32) ?: 32
        dirty = savedInstanceState?.getBoolean("dirty", false) ?: false
        updatePreview()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        loadImage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::input.isInitialized) {
            outState.putString("text", input.text.toString())
            outState.putInt("size", sizeControl.progress)
            outState.putInt("color", colorIndex)
            outState.putFloat("x", positionX)
            outState.putFloat("y", positionY)
            outState.putBoolean("dirty", dirty)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Pekerjaan IO yang sedang menyimpan mungkin masih memakai bitmap ini.
        if (::preview.isInitialized && !saving) preview.bitmap?.recycle()
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
        bar.addView(Ui.iconButton(c, R.drawable.ic_close, "Tutup editor teks").apply {
            setOnClickListener { closeEditor() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(Ui.text(c, "Tambah teks", 18f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        saveAction = Ui.text(c, "Simpan", 15f, primary, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            isEnabled = false
            alpha = 0.4f
            contentDescription = "Simpan teks pada halaman"
            setOnClickListener { save() }
        }
        bar.addView(saveAction, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(bar)

        val scroll = ScrollView(c).apply { isFillViewport = true }
        val content = Ui.column(c).apply { setPadding(dp(20), dp(6), dp(20), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(Ui.text(c, "Ketik satu baris, lalu ketuk atau geser pada halaman untuk mengatur posisi.", 13f, muted).apply {
            setPadding(0, 0, 0, dp(8))
        })
        input = EditText(c).apply {
            hint = "Tulis teks di sini"
            contentDescription = "Teks yang akan ditambahkan"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(100))
            setTextColor(onSurface)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.bg(Ui.color(c, R.color.md_surface_container_lowest), dp(12).toFloat(),
                Ui.color(c, R.color.md_outline_variant), dp(1))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    dirty = true
                    if (::preview.isInitialized && ::sizeControl.isInitialized) updatePreview()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        preview = TextOverlayView(c).apply {
            setBackgroundColor(Ui.color(c, R.color.page_background))
            onPositionChanged = { x, y -> positionX = x; positionY = y; dirty = true }
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(320)).apply { topMargin = dp(12) })

        val sizeRow = Ui.row(c).apply { setPadding(0, dp(14), 0, 0) }
        sizeRow.addView(Ui.text(c, "Ukuran teks", 14f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        sizeLabel = Ui.text(c, "", 12f, muted)
        sizeRow.addView(sizeLabel)
        content.addView(sizeRow)
        sizeControl = SeekBar(c).apply {
            max = 100
            contentDescription = "Ukuran teks"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) dirty = true
                    if (::colorButtons.isInitialized) updatePreview()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        content.addView(sizeControl, LinearLayout.LayoutParams(-1, dp(44)))
        val colorRow = Ui.row(c)
        colorRow.addView(Ui.text(c, "Warna teks", 14f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        colorRow.addView(Ui.button(c, "Posisi", outlined = true).apply {
            setOnClickListener { choosePosition() }
        }, LinearLayout.LayoutParams(dp(92), dp(48)))
        content.addView(colorRow)
        val buttons = Ui.row(c)
        colorButtons = listOf("Hitam", "Biru", "Merah").mapIndexed { index, label ->
            Ui.button(c, label, outlined = true).apply {
                setPadding(dp(4), 0, dp(4), 0)
                contentDescription = "Warna $label"
                setOnClickListener { colorIndex = index; dirty = true; updatePreview() }
                buttons.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        }
        content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        content.addView(Ui.text(c, "Teks yang disimpan menyatu dengan gambar dan tidak dapat diedit lagi secara terpisah.", 12f, muted).apply {
            setPadding(0, dp(12), 0, 0)
        })
        setScreen(root)
    }

    private fun currentSpec(): TextOverlaySpec = TextOverlaySpec(
        text = input.text.toString().replace('\n', ' ').replace('\r', ' '),
        x = positionX, y = positionY,
        sizeFraction = 0.03f + sizeControl.progress * 0.0009f,
        color = colors[colorIndex]
    )

    private fun updatePreview() {
        preview.spec = currentSpec()
        sizeLabel.text = "Maks. ${(3f + sizeControl.progress * 0.09f).roundToInt()}% lebar"
        colorButtons.forEachIndexed { index, button ->
            val selected = index == colorIndex
            button.backgroundTintList = ColorStateList.valueOf(if (selected) colors[index] else Color.TRANSPARENT)
            button.setTextColor(if (selected) Color.WHITE else Ui.color(this, R.color.md_on_surface))
            button.isSelected = selected
        }
        val canSave = preview.bitmap != null && input.text.toString().isNotBlank() && !saving
        saveAction.isEnabled = canSave
        saveAction.alpha = if (canSave) 1f else 0.4f
    }

    private fun choosePosition() {
        val choices = arrayOf("Kiri atas", "Tengah atas", "Kanan atas", "Kiri tengah", "Tengah", "Kanan tengah", "Kiri bawah", "Tengah bawah", "Kanan bawah")
        AlertDialog.Builder(this).setTitle("Posisi teks").setItems(choices) { _, which ->
            positionX = (which % 3) / 2f
            positionY = (which / 3) / 2f
            dirty = true
            updatePreview()
        }.setNegativeButton("Batal", null).show()
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
                preview.setImage(bitmap)
                updatePreview()
            } catch (e: Exception) {
                busy(false)
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this@TextEditActivity).setTitle("Halaman tidak dapat dibuka")
                        .setMessage(e.localizedMessage ?: "Silakan coba lagi.")
                        .setPositiveButton("Kembali") { _, _ -> finish() }
                        .setOnCancelListener { finish() }.show()
                }
            }
        }
    }

    private fun save() {
        val source = preview.bitmap ?: return
        val spec = currentSpec()
        if (spec.text.isBlank() || saving) return
        saving = true
        updatePreview()
        busy(true, "Menyimpan teks…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val result = TextOverlayRenderer.render(source, spec)
                    try { DocumentStore.replacePage(applicationContext, docId, page, result) }
                    finally { result.recycle() }
                }
                busy(false)
                saving = false
                dirty = false
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                busy(false)
                saving = false
                updatePreview()
                error("Teks tidak dapat disimpan.", e)
            }
        }
    }

    private fun closeEditor() {
        if (saving) return
        if (!dirty || input.text.toString().isBlank()) { finish(); return }
        AlertDialog.Builder(this).setTitle("Buang teks?")
            .setMessage("Teks yang belum disimpan akan hilang.")
            .setNegativeButton("Lanjut mengedit", null)
            .setPositiveButton("Buang") { _, _ -> finish() }.show()
    }
}
