package com.dharmawantoxi.docscan.ocr

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.dharmawantoxi.docscan.office.OfficeExport
import com.dharmawantoxi.docscan.office.OfficeFormat
import com.dharmawantoxi.docscan.office.OfficePackage
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Language
import com.dharmawantoxi.docscan.ui.Ui
import com.google.android.material.button.MaterialButton
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
import java.io.File

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
    private var pendingOfficeFormat: OfficeFormat? = null
    private val officeButtons = mutableListOf<MaterialButton>()

    private val saveTextLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveText(uri)
    }
    private val saveOfficeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val format = pendingOfficeFormat
        pendingOfficeFormat = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && format != null) saveOffice(format, uri)
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
            status.text = if (editor.text.isNullOrBlank()) tr("Tidak ada teks yang dikenali.", "No text was recognized.") else tr("Selesai. Anda dapat memperbaiki teks di bawah.", "Done. You can edit the text below.")
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
        bar.addView(Ui.iconButton(c, R.drawable.ic_arrow_back, tr("Kembali", "Back")).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = Ui.column(c)
        heading.addView(Ui.text(c, tr("Pindai teks", "Scan text"), 18f, onSurface, true))
        heading.addView(Ui.text(c, tr("Pengenalan teks di perangkat", "On-device text recognition"), 12f, muted))
        bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val content = Ui.column(c).apply { setPadding(dp(20), dp(10), dp(20), dp(20)) }
        val note = Ui.column(c).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        note.addView(Ui.text(c, tr("HASIL OCR", "OCR RESULT"), 10f, Ui.color(c, R.color.md_on_primary_container), true).apply { letterSpacing = 0.08f })
        note.addView(Ui.text(c, tr("Teks Latin (termasuk Bahasa Indonesia) dikenali di perangkat. Periksa kembali hasilnya sebelum digunakan.", "Latin text (including Indonesian) is recognized on this device. Check the result before using it."), 13f,
            Ui.color(c, R.color.md_on_primary_container)).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(note)
        status = Ui.text(c, tr("Menyiapkan pengenalan teks…", "Preparing text recognition…"), 13f, muted).apply { setPadding(0, dp(17), 0, dp(8)) }
        content.addView(status)
        progress = LinearProgressIndicator(c).apply { isIndeterminate = true }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        editor = EditText(c).apply {
            gravity = Gravity.TOP or Gravity.START
            hint = tr("Teks hasil pengenalan akan muncul di sini.", "Recognized text will appear here.")
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
        copyButton = Ui.button(c, tr("Salin", "Copy"), R.drawable.ic_copy, outlined = true).apply {
            setOnClickListener {
                val value = editor.text.toString()
                if (value.isBlank()) { toast(tr("Belum ada teks untuk disalin.", "There is no text to copy yet.")); return@setOnClickListener }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(tr("Teks dokumen", "Document text"), value))
                toast(tr("Teks disalin.", "Text copied."))
            }
        }
        actions.addView(copyButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        saveButton = Ui.button(c, tr("Simpan TXT", "Save TXT"), R.drawable.ic_download).apply {
            setOnClickListener {
                if (editor.text.isNullOrBlank()) { toast(tr("Belum ada teks untuk disimpan.", "There is no text to save yet.")); return@setOnClickListener }
                try { saveTextLauncher.launch("${DocumentNames.fileName(title)}.txt") }
                catch (_: ActivityNotFoundException) { error(tr("Pemilih lokasi penyimpanan tidak tersedia.", "No save-location picker is available.")) }
            }
        }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        content.addView(actions)

        val officeRow = Ui.row(c).apply { setPadding(0, dp(8), 0, 0) }
        OfficeFormat.entries.forEachIndexed { index, format ->
            val button = Ui.button(c, format.displayName, null, outlined = true).apply {
                contentDescription = tr("Ekspor teks OCR ke ${format.displayName}", "Export OCR text to ${format.displayName}")
                setOnClickListener { requestOfficeExport(format) }
            }
            officeButtons.add(button)
            officeRow.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        content.addView(officeRow)
        content.addView(Ui.text(c, tr("Ekspor teks yang dikenali menjadi dokumen Office yang dapat diedit. Tata letak, foto, dan tabel tidak direkonstruksi; Excel memisahkan kolom pada tab atau beberapa spasi.", "Export recognized text to an editable Office document. Layout, photos, and tables are not reconstructed; Excel splits columns at tabs or multiple spaces."), 12f, muted).apply {
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreen(root)
        setResultEnabled(false)
    }

    private fun setResultEnabled(enabled: Boolean) {
        editor.isEnabled = enabled
        copyButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        officeButtons.forEach { it.isEnabled = enabled }
    }

    private fun recognize() {
        lifecycleScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val doc = withContext(Dispatchers.IO) { DocumentStore.get(applicationContext, docId) }
                    ?: throw IllegalStateException(tr("Dokumen tidak ditemukan", "Document not found"))
                title = doc.title
                val results = mutableListOf<String>()
                for ((index, page) in doc.pages.withIndex()) {
                    status.text = tr("Membaca halaman ${index + 1} dari ${doc.pages.size}…", "Reading page ${index + 1} of ${doc.pages.size}…")
                    val input = withContext(Dispatchers.IO) {
                        InputImage.fromFilePath(applicationContext,
                            Uri.fromFile(DocumentStore.pageFile(applicationContext, doc.id, page)))
                    }
                    results.add(recognizer.readText(input).trim())
                }
                val output = if (results.all { it.isBlank() }) "" else if (results.size == 1) results.first()
                    else results.mapIndexed { index, text ->
                        tr("--- Halaman ${index + 1} ---\n", "--- Page ${index + 1} ---\n") + text.ifBlank { tr("[Tidak ada teks yang dikenali]", "[No text recognized]") }
                    }.joinToString("\n\n")
                editor.setText(output)
                completed = true
                progress.visibility = View.GONE
                status.text = if (output.isBlank()) tr("Tidak ada teks yang dikenali. Coba foto yang lebih jelas.", "No text was recognized. Try a clearer photo.")
                    else tr("Selesai. Anda dapat memperbaiki teks di bawah.", "Done. You can edit the text below.")
                setResultEnabled(true)
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = tr("Pengenalan teks belum berhasil.", "Text recognition was unsuccessful.")
                error(tr("Teks tidak dapat dibaca. Pastikan semua halaman dapat dibuka.", "Text could not be read. Make sure all pages can be opened."), e)
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

    private fun requestOfficeExport(format: OfficeFormat) {
        val value = editor.text.toString()
        if (value.isBlank()) { toast(tr("Belum ada teks untuk diekspor.", "There is no text to export yet.")); return }
        val pages = try { OfficeExport.splitPages(value) } catch (e: Exception) {
            error(tr("Teks tidak dapat diekspor.", "Text could not be exported."), e); return
        }
        if (pages.all { it.isBlank() }) { toast(tr("Belum ada teks yang dikenali untuk diekspor.", "There is no recognized text to export yet.")); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = format.mime
            putExtra(Intent.EXTRA_TITLE, "${DocumentNames.fileName(title)}.${format.extension}")
        }
        pendingOfficeFormat = format
        try { saveOfficeLauncher.launch(intent) } catch (_: ActivityNotFoundException) {
            pendingOfficeFormat = null
            error(tr("Pemilih lokasi penyimpanan tidak tersedia.", "No save-location picker is available."))
        }
    }

    private fun saveOffice(format: OfficeFormat, uri: Uri) {
        val value = editor.text.toString()
        busy(true, tr("Membuat ${format.displayName}…", "Creating ${format.displayName}…"))
        lifecycleScope.launch {
            val temporary = File.createTempFile("export-", ".${format.extension}", cacheDir)
            try {
                withContext(Dispatchers.IO) {
                    val pages = OfficeExport.splitPages(value)
                    if (pages.all { it.isBlank() }) throw IllegalStateException(tr("Belum ada teks yang dikenali", "No text has been recognized"))
                    temporary.outputStream().use { OfficeExport.write(format, pages, it, Language.code(applicationContext) == Language.ENGLISH) }
                    // Pastikan paket hasil dapat dibaca dan formatnya sesuai sebelum disalin.
                    if (OfficePackage.inspect(temporary).format != format) {
                        throw IllegalStateException(tr("Hasil ${format.displayName} tidak valid", "The ${format.displayName} output is invalid"))
                    }
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException(tr("Lokasi penyimpanan tidak dapat dibuka", "The save location could not be opened"))
                    temporary.inputStream().use { input -> output.use { input.copyTo(it) } }
                }
                busy(false); toast(tr("${format.displayName} berhasil disimpan.", "${format.displayName} saved successfully."))
            } catch (e: Exception) {
                busy(false); error(tr("${format.displayName} tidak dapat disimpan.", "${format.displayName} could not be saved."), e)
            } finally { temporary.delete() }
        }
    }

    private fun saveText(uri: Uri) {
        val text = editor.text.toString()
        busy(true, tr("Menyimpan teks…", "Saving text…"))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException(tr("Lokasi penyimpanan tidak dapat dibuka", "The save location could not be opened"))
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                busy(false); toast(tr("Teks berhasil disimpan.", "Text saved successfully."))
            } catch (e: Exception) { busy(false); error(tr("Teks tidak dapat disimpan.", "Text could not be saved."), e) }
        }
    }
}
