package com.dharmawantoxi.docscan.ocr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.DocumentActivity
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.data.DocumentNames
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OCR Latin berjalan di perangkat; teks dapat disunting sebelum disalin/disimpan. */
class OcrActivity : BaseActivity() {
    private lateinit var docId: String
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var copyButton: View
    private lateinit var saveButton: View
    private var completed = false
    private var title = "Dokumen"

    private val saveTextLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveText(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        docId = intent.getStringExtra(DocumentActivity.EXTRA_ID).orEmpty()
        if (docId.isBlank()) { finish(); return }
        buildScreen()
        if (savedInstanceState?.getBoolean("completed") == true) {
            completed = true
            title = savedInstanceState.getString("title", "Dokumen")
            editor.setText(savedInstanceState.getString("text", ""))
            status.text = if (editor.text.isNullOrBlank()) "Tidak ada teks yang dikenali." else "Selesai. Anda dapat memperbaiki teks di bawah."
            progress.visibility = View.GONE
            setResultEnabled(true)
        } else recognize()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) {
            outState.putBoolean("completed", completed)
            outState.putString("title", title)
            outState.putString("text", editor.text.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val onSurface = Ui.color(c, R.color.md_on_surface)
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val root = Ui.column(c).apply { setBackgroundColor(Ui.color(c, R.color.md_background)) }
        val bar = Ui.row(c).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        bar.addView(Ui.iconButton(c, R.drawable.ic_arrow_back, "Kembali").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = Ui.column(c)
        heading.addView(Ui.text(c, "Pindai teks", 18f, onSurface, true))
        heading.addView(Ui.text(c, "Pengenalan teks di perangkat", 12f, muted))
        bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val content = Ui.column(c).apply { setPadding(dp(20), dp(10), dp(20), dp(20)) }
        val note = Ui.column(c).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        note.addView(Ui.text(c, "HASIL OCR", 10f, Ui.color(c, R.color.md_on_primary_container), true).apply { letterSpacing = 0.08f })
        note.addView(Ui.text(c, "Teks Latin (termasuk Bahasa Indonesia) dikenali di perangkat. Periksa kembali hasilnya sebelum digunakan.", 13f,
            Ui.color(c, R.color.md_on_primary_container)).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(note)
        status = Ui.text(c, "Menyiapkan pengenalan teks…", 13f, muted).apply { setPadding(0, dp(17), 0, dp(8)) }
        content.addView(status)
        progress = LinearProgressIndicator(c).apply { isIndeterminate = true }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        editor = EditText(c).apply {
            gravity = Gravity.TOP or Gravity.START
            hint = "Teks hasil pengenalan akan muncul di sini."
            setTextSize(15f)
            setTextColor(onSurface)
            setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            background = Ui.bg(Ui.color(c, R.color.md_surface_container_lowest), dp(16).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isEnabled = false
        }
        content.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        val actions = Ui.row(c).apply { setPadding(0, dp(14), 0, 0) }
        copyButton = Ui.button(c, "Salin", R.drawable.ic_copy, outlined = true).apply {
            setOnClickListener {
                val value = editor.text.toString()
                if (value.isBlank()) { toast("Belum ada teks untuk disalin."); return@setOnClickListener }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Teks dokumen", value))
                toast("Teks disalin.")
            }
        }
        actions.addView(copyButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        saveButton = Ui.button(c, "Simpan TXT", R.drawable.ic_download).apply {
            setOnClickListener {
                if (editor.text.isNullOrBlank()) { toast("Belum ada teks untuk disimpan."); return@setOnClickListener }
                try { saveTextLauncher.launch("${DocumentNames.fileName(title)}.txt") }
                catch (_: ActivityNotFoundException) { error("Pemilih lokasi penyimpanan tidak tersedia.") }
            }
        }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        content.addView(actions)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreen(root)
        setResultEnabled(false)
    }

    private fun setResultEnabled(enabled: Boolean) {
        editor.isEnabled = enabled
        copyButton.isEnabled = enabled
        saveButton.isEnabled = enabled
    }

    private fun recognize() {
        lifecycleScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val doc = withContext(Dispatchers.IO) { DocumentStore.get(applicationContext, docId) }
                    ?: throw IllegalStateException("Dokumen tidak ditemukan")
                title = doc.title
                val results = mutableListOf<String>()
                for ((index, page) in doc.pages.withIndex()) {
                    status.text = "Membaca halaman ${index + 1} dari ${doc.pages.size}…"
                    val input = withContext(Dispatchers.IO) {
                        InputImage.fromFilePath(applicationContext,
                            Uri.fromFile(DocumentStore.pageFile(applicationContext, doc.id, page)))
                    }
                    results.add(recognizer.readText(input).trim())
                }
                val output = if (results.all { it.isBlank() }) "" else if (results.size == 1) results.first()
                    else results.mapIndexed { index, text ->
                        "--- Halaman ${index + 1} ---\n" + text.ifBlank { "[Tidak ada teks yang dikenali]" }
                    }.joinToString("\n\n")
                editor.setText(output)
                completed = true
                progress.visibility = View.GONE
                status.text = if (output.isBlank()) "Tidak ada teks yang dikenali. Coba foto yang lebih jelas."
                    else "Selesai. Anda dapat memperbaiki teks di bawah."
                setResultEnabled(true)
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "Pengenalan teks belum berhasil."
                error("Teks tidak dapat dibaca. Pastikan semua halaman dapat dibuka.", e)
            } finally {
                recognizer.close()
            }
        }
    }

    private suspend fun TextRecognizer.readText(image: InputImage): String = suspendCancellableCoroutine { continuation ->
        try {
            process(image)
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result.text) }
                .addOnFailureListener { e -> if (continuation.isActive) continuation.resumeWithException(e) }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    private fun saveText(uri: Uri) {
        val text = editor.text.toString()
        busy(true, "Menyimpan teks…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    try {
                        val output = contentResolver.openOutputStream(uri, "w")
                            ?: throw IllegalStateException("Lokasi penyimpanan tidak dapat dibuka")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    } catch (e: Exception) {
                        runCatching { contentResolver.delete(uri, null, null) }
                        throw e
                    }
                }
                busy(false); toast("Teks berhasil disimpan.")
            } catch (e: Exception) { busy(false); error("Teks tidak dapat disimpan.", e) }
        }
    }
}
