package com.dharmawantoxi.docscan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.data.Document
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.office.OfficeActivity
import com.dharmawantoxi.docscan.zip.ZipActivity
import com.dharmawantoxi.docscan.pdf.PdfMergeActivity
import com.dharmawantoxi.docscan.pdf.NativePdfActivity
import com.dharmawantoxi.docscan.scan.ScanHostActivity
import com.dharmawantoxi.docscan.ui.DocumentDialogs
import com.dharmawantoxi.docscan.ui.Ui
import com.dharmawantoxi.docscan.ui.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ScanHostActivity() {
    private lateinit var documentList: LinearLayout
    private lateinit var documentCount: android.widget.TextView
    private lateinit var totalPages: android.widget.TextView
    private lateinit var sectionCount: android.widget.TextView
    private var documents: List<Document> = emptyList()
    private var query = ""
    private var refreshVersion = 0
    private val openPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }
    private val openNativePdfLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val granted = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // Beberapa provider hanya memberikan izin sementara; menyimpan salinan tetap dapat digunakan.
            if (granted != 0) runCatching { contentResolver.takePersistableUriPermission(uri, granted) }
            startActivity(Intent(this, NativePdfActivity::class.java).apply {
                this.data = uri
                clipData = ClipData.newRawUri("PDF", uri)
                addFlags(granted)
                putExtra(NativePdfActivity.EXTRA_CAN_WRITE, granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            })
        }
    }
    private val openOfficeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val granted = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (granted != 0) runCatching { contentResolver.takePersistableUriPermission(uri, granted) }
            startActivity(Intent(this, OfficeActivity::class.java).apply {
                this.data = uri
                clipData = ClipData.newRawUri("Office", uri)
                addFlags(granted)
                putExtra(OfficeActivity.EXTRA_CAN_WRITE, granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun availablePages() = DocumentStore.MAX_PAGES

    override fun onImagesSelected(uris: List<Uri>) {
        busy(true, tr("Menyimpan halaman…", "Saving pages…"))
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { DocumentStore.create(applicationContext, uris) }
                busy(false)
                startActivity(Intent(this@MainActivity, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_ID, doc.id))
            } catch (e: Exception) {
                busy(false)
                error(tr("Dokumen tidak dapat disimpan. Pastikan foto dapat dibuka dan ruang penyimpanan cukup.", "The document could not be saved. Make sure the photos can be opened and there is enough storage."), e)
            } finally {
                cleanCameraInputs(uris)
            }
        }
    }

    private fun showPdfImportInfo() {
        AlertDialog.Builder(this).setTitle(tr("Edit teks PDF asli", "Edit original PDF text"))
            .setMessage(tr("Ganti teks yang sudah ada tanpa mengubah halaman menjadi gambar. Teks pada hasil pindai/gambar, formulir, XObject, atau font tertentu mungkin tidak dapat diedit; tata letak tidak mengalir ulang otomatis. PDF bertanda tangan atau dilindungi tidak didukung.\n\nFile asli baru diganti setelah Anda menyimpan cadangan dan menyetujui penggantian. Anda juga dapat menyimpan salinan. Maksimal 25 halaman dan 100 MB.", "Replace existing text without turning pages into images. Text in scans or images, forms, XObjects, or certain fonts may not be editable; layout does not reflow automatically. Signed or protected PDFs are not supported.\n\nThe original is replaced only after you save a backup and confirm. You can also save a copy. Maximum 25 pages and 100 MB."))
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .setNeutralButton(tr("Edit sebagai gambar", "Edit as images")) { _, _ -> showRasterPdfInfo() }
            .setPositiveButton(tr("Pilih PDF", "Choose PDF")) { _, _ ->
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try { openNativePdfLauncher.launch(picker) }
                catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia di perangkat ini.", "No document picker is available on this device.")) }
            }.show()
    }

    private fun showRasterPdfInfo() {
        AlertDialog.Builder(this).setTitle(tr("Edit PDF sebagai gambar", "Edit PDF as images"))
            .setMessage(tr("PDF akan diimpor sebagai salinan. Setiap halaman diubah menjadi gambar: teks asli tidak dapat diedit atau dicari, dan tautan, formulir serta tanda tangan digital tidak dipertahankan.\n\nFile sumber tidak diubah saat impor. Pilih nama atau lokasi baru saat mengekspor. Maksimal 25 halaman dan 100 MB; PDF dengan kata sandi tidak didukung.", "The PDF will be imported as a copy. Each page becomes an image: original text cannot be edited or searched, and links, forms, and digital signatures are not retained.\n\nThe source is not changed during import. Choose a new name or location when exporting. Maximum 25 pages and 100 MB; password-protected PDFs are not supported."))
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .setPositiveButton(tr("Pilih PDF", "Choose PDF")) { _, _ ->
                try { openPdfLauncher.launch(arrayOf("application/pdf")) }
                catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia di perangkat ini.", "No document picker is available on this device.")) }
            }.show()
    }

    private fun showOfficeInfo() {
        AlertDialog.Builder(this).setTitle(tr("Edit file Word, Excel, PowerPoint", "Edit Word, Excel, PowerPoint files"))
            .setMessage(tr("Ganti teks DOCX, isi sel XLSX, atau teks slide PPTX yang sudah ada tanpa menjadikannya gambar. Bagian lain file (gambar, lembar, slide, gaya, formula) dipertahankan, tetapi editor ini berupa daftar isi, bukan pratinjau tata letak. Formula, konten tersembunyi, dan beberapa jenis teks dilewati; teks panjang dapat mengubah tata letak.\n\nHanya format DOCX, XLSX, dan PPTX biasa yang didukung; format lama (.doc, .xls, .ppt), file dilindungi, bertanda tangan, atau bermakro ditolak. File asli baru diganti setelah Anda menyimpan cadangan dan menyetujui penggantian. Anda juga dapat menyimpan salinan. Maksimal 100 MB.", "Replace existing DOCX text, XLSX cells, or PPTX slide text without turning them into images. Other parts (images, sheets, slides, styles, and formulas) are preserved, but this editor is a content list, not a layout preview. Formulas, hidden content, and some text types are skipped; long text may change the layout.\n\nOnly regular DOCX, XLSX, and PPTX files are supported; old formats (.doc, .xls, .ppt), protected, signed, or macro-enabled files are rejected. The original is replaced only after you save a backup and confirm. You can also save a copy. Maximum 100 MB."))
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .setPositiveButton(tr("Pilih file", "Choose file")) { _, _ ->
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try { openOfficeLauncher.launch(picker) }
                catch (_: ActivityNotFoundException) { error(tr("Pemilih dokumen tidak tersedia di perangkat ini.", "No document picker is available on this device.")) }
            }.show()
    }

    private fun importPdf(uri: Uri) {
        busy(true, tr("Mengimpor halaman PDF…", "Importing PDF pages…"))
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { DocumentStore.importPdf(applicationContext, uri) }
                busy(false)
                startActivity(Intent(this@MainActivity, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_ID, doc.id))
            } catch (e: Exception) {
                busy(false)
                error(tr("PDF tidak dapat diimpor. Pastikan file valid, tidak memiliki kata sandi, dan ruang penyimpanan cukup.", "The PDF could not be imported. Make sure it is valid, has no password, and there is enough storage."), e)
            }
        }
    }

    private fun buildScreen() {
        val c = this
        val backgroundColor = Ui.color(c, R.color.md_background)
        val surface = Ui.color(c, R.color.md_surface_container_lowest)
        val onSurface = Ui.color(c, R.color.md_on_surface)
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val primary = Ui.color(c, R.color.md_primary)
        val dp = { n: Int -> Ui.dp(c, n) }
        val root = Ui.column(c).apply { setBackgroundColor(backgroundColor) }
        val scroll = ScrollView(c).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val content = Ui.column(c).apply { setPadding(dp(22), dp(16), dp(22), dp(32)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreen(root)

        // Bar identitas.
        val header = Ui.row(c)
        val brand = ImageView(c).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.bg(primary, dp(14).toFloat())
            clipToOutline = true
            contentDescription = tr("Ikon aplikasi DocScan Toxi", "DocScan Toxi app icon")
        }
        header.addView(brand, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brandText = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        brandText.addView(Ui.text(c, "DocScan Toxi", 19f, onSurface, true))
        brandText.addView(Ui.text(c, tr("Dokumen rapi, kapan saja.", "Organized documents, anytime."), 12f, muted))
        header.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.iconButton(c, R.drawable.ic_language, "Bahasa / Language").apply {
            setOnClickListener { chooseLanguage() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(Ui.iconButton(c, R.drawable.ic_info, tr("Tentang aplikasi", "About the app")).apply {
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(header)

        // Hero berwarna biru, dengan ilustrasi kertas dari bentuk vektor lokal.
        val hero = Ui.column(c).apply {
            background = Ui.gradient(0xFF0D3970.toInt(), 0xFF136CC5.toInt(), dp(24).toFloat())
            setPadding(dp(20), dp(21), dp(20), dp(20))
        }
        val heroRow = Ui.row(c)
        val heroText = Ui.column(c)
        heroText.addView(Ui.text(c, tr("RUANG DOKUMEN DIGITAL", "YOUR DIGITAL DOCUMENT SPACE"), 10f, 0xFFB7D8FF.toInt(), true).apply { letterSpacing = 0.08f })
        heroText.addView(Ui.text(c, tr("Pindai mudah,\nsimpan rapi.", "Scan with ease,\nstay organized."), 25f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(5)); setLineSpacing(0f, 1.03f)
        })
        heroText.addView(Ui.text(c, tr("Ubah lembaran jadi dokumen siap dibagikan.", "Turn pages into documents ready to share."), 13f, 0xFFE1EDFF.toInt()).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f))
        heroRow.addView(makePaperIllustration(), LinearLayout.LayoutParams(dp(84), dp(110)).apply { leftMargin = dp(8) })
        hero.addView(heroRow)
        val heroActions = Ui.row(c).apply { setPadding(0, dp(18), 0, 0) }
        val scan = Ui.button(c, tr("Mulai pindai", "Start scanning"), R.drawable.ic_scan).apply {
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(0xFF124F98.toInt())
            iconTint = ColorStateList.valueOf(0xFF124F98.toInt())
            setOnClickListener { startScanner() }
        }
        heroActions.addView(scan, LinearLayout.LayoutParams(0, dp(50), 1.25f))
        heroActions.addView(Ui.button(c, tr("Impor foto", "Import photos"), R.drawable.ic_image, onDark = true).apply {
            setOnClickListener { startGallery() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        hero.addView(heroActions)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val editPdf = Ui.row(c).apply {
            background = Ui.bg(surface, dp(18).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true; isFocusable = true
            contentDescription = tr("Edit PDF yang sudah ada", "Edit an existing PDF")
            setOnClickListener { showPdfImportInfo() }
        }
        editPdf.addView(Ui.image(c, R.drawable.ic_pdf, primary).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(12).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val editPdfText = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        editPdfText.addView(Ui.text(c, tr("Edit PDF", "Edit PDF"), 15f, onSurface, true))
        editPdfText.addView(Ui.text(c, tr("Ganti teks PDF asli atau atur halaman", "Replace original PDF text or arrange pages"), 12f, muted))
        editPdf.addView(editPdfText, LinearLayout.LayoutParams(0, -2, 1f))
        editPdf.addView(Ui.image(c, R.drawable.ic_arrow_back, muted).apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        content.addView(editPdf, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val mergePdf = Ui.row(c).apply {
            background = Ui.bg(surface, dp(18).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true; isFocusable = true
            contentDescription = tr("Gabungkan beberapa PDF", "Merge multiple PDFs")
            setOnClickListener { startActivity(Intent(c, PdfMergeActivity::class.java)) }
        }
        mergePdf.addView(Ui.image(c, R.drawable.ic_pdf, primary).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(12).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val mergeText = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        mergeText.addView(Ui.text(c, tr("Gabung PDF", "Merge PDFs"), 15f, onSurface, true))
        mergeText.addView(Ui.text(c, tr("Satukan beberapa PDF sesuai urutan", "Combine PDFs in your chosen order"), 12f, muted))
        mergePdf.addView(mergeText, LinearLayout.LayoutParams(0, -2, 1f))
        mergePdf.addView(Ui.image(c, R.drawable.ic_arrow_back, muted).apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        content.addView(mergePdf, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val zipCard = Ui.row(c).apply {
            background = Ui.bg(surface, dp(18).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true; isFocusable = true
            contentDescription = tr("Buat atau ekstrak ZIP", "Create or extract ZIP")
            setOnClickListener { startActivity(Intent(c, ZipActivity::class.java)) }
        }
        zipCard.addView(Ui.image(c, R.drawable.ic_zip, primary).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(12).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val zipText = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        zipText.addView(Ui.text(c, "ZIP", 15f, onSurface, true))
        zipText.addView(Ui.text(c, tr("Buat arsip atau ekstrak file ZIP", "Create an archive or extract a ZIP file"), 12f, muted))
        zipCard.addView(zipText, LinearLayout.LayoutParams(0, -2, 1f))
        zipCard.addView(Ui.image(c, R.drawable.ic_arrow_back, muted).apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        content.addView(zipCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val editOffice = Ui.row(c).apply {
            background = Ui.bg(surface, dp(18).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true; isFocusable = true
            contentDescription = tr("Edit file Word, Excel, atau PowerPoint", "Edit a Word, Excel, or PowerPoint file")
            setOnClickListener { showOfficeInfo() }
        }
        editOffice.addView(Ui.image(c, R.drawable.ic_office, primary).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(12).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val editOfficeText = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        editOfficeText.addView(Ui.text(c, tr("Edit Office", "Edit Office"), 15f, onSurface, true))
        editOfficeText.addView(Ui.text(c, tr("Word, Excel, dan PowerPoint (.docx/.xlsx/.pptx)", "Word, Excel, and PowerPoint (.docx/.xlsx/.pptx)"), 12f, muted))
        editOffice.addView(editOfficeText, LinearLayout.LayoutParams(0, -2, 1f))
        editOffice.addView(Ui.image(c, R.drawable.ic_arrow_back, muted).apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        content.addView(editOffice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val stats = Ui.row(c).apply {
            background = Ui.bg(surface, dp(18).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val docStat = Ui.column(c)
        documentCount = Ui.text(c, "0", 20f, onSurface, true)
        docStat.addView(documentCount)
        docStat.addView(Ui.text(c, tr("Dokumen", "Documents"), 12f, muted))
        stats.addView(docStat, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(View(c).apply { setBackgroundColor(Ui.color(c, R.color.md_outline_variant)) },
            LinearLayout.LayoutParams(dp(1), dp(34)))
        val pageStat = Ui.column(c).apply { setPadding(dp(20), 0, 0, 0) }
        totalPages = Ui.text(c, "0", 20f, onSurface, true)
        pageStat.addView(totalPages)
        pageStat.addView(Ui.text(c, tr("Halaman", "Pages"), 12f, muted))
        stats.addView(pageStat, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(Ui.image(c, R.drawable.ic_layers, primary).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(12).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = tr("Disimpan di perangkat", "Stored on this device")
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val listHeader = Ui.row(c).apply { setPadding(0, dp(25), 0, dp(12)) }
        listHeader.addView(Ui.text(c, tr("Dokumen saya", "My documents"), 19f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        sectionCount = Ui.text(c, tr("0 dokumen", "0 documents"), 12f, muted)
        listHeader.addView(sectionCount)
        content.addView(listHeader)
        val search = EditText(c).apply {
            hint = tr("Cari nama dokumen", "Search documents by name")
            setSingleLine(true)
            setTextSize(14f)
            setTextColor(onSurface)
            setHintTextColor(muted)
            background = Ui.bg(surface, dp(14).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(15), 0, dp(15), 0)
            val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(c, R.drawable.ic_search)?.mutate()
            icon?.setTint(muted)
            icon?.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty().trim()
                    renderDocuments()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        documentList = Ui.column(c)
        content.addView(documentList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun makePaperIllustration(): View {
        val dp = { n: Int -> Ui.dp(this, n) }
        val art = FrameLayout(this).apply {
            background = Ui.bg(0x2459B5FF, dp(22).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val paper = Ui.column(this).apply {
            background = Ui.bg(Color.WHITE, dp(6).toFloat())
            rotation = -7f
            setPadding(dp(9), dp(14), dp(9), dp(10))
            elevation = dp(4).toFloat()
        }
        listOf(25, 30, 22, 28).forEachIndexed { index, width ->
            paper.addView(View(this).apply { background = Ui.bg(if (index == 0) 0xFF7DB5F1.toInt() else 0xFFD5E5F7.toInt(), dp(2).toFloat()) },
                LinearLayout.LayoutParams(dp(width), dp(if (index == 0) 4 else 3)).apply { bottomMargin = dp(7) })
        }
        art.addView(paper, FrameLayout.LayoutParams(dp(53), dp(73), Gravity.CENTER))
        val badge = Ui.image(this, R.drawable.ic_check, Color.WHITE).apply {
            background = Ui.bg(0xFF23B69C.toInt(), dp(12).toFloat())
            setPadding(dp(5), dp(5), dp(5), dp(5))
            elevation = dp(6).toFloat()
        }
        art.addView(badge, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(4); bottomMargin = dp(7)
        })
        return art
    }

    private fun chooseLanguage() {
        val current = if (Language.code(this) == Language.ENGLISH) 1 else 0
        AlertDialog.Builder(this).setTitle("Bahasa / Language")
            .setSingleChoiceItems(arrayOf("Bahasa Indonesia", "English"), current) { dialog, which ->
                val selected = if (which == 1) Language.ENGLISH else Language.INDONESIAN
                dialog.dismiss()
                if (selected != Language.code(this)) {
                    Language.set(this, selected)
                    recreate()
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null).show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle(tr("Tentang DocScan Toxi", "About DocScan Toxi"))
            .setMessage(tr("Pindai dan simpan dokumen sebagai PDF atau gambar, lalu bagikan bila diperlukan.\n\nDokumen disimpan di perangkat ini dan akan terhapus jika aplikasi dihapus. Simpan salinan melalui tombol ekspor.\n\nPemindai otomatis memerlukan Google Play Services; kamera biasa dan impor foto tersedia sebagai alternatif.", "Scan and save documents as PDF or images, then share them if needed.\n\nDocuments are stored on this device and will be deleted if the app is removed. Use an export button to save a copy.\n\nAutomatic scanning requires Google Play Services; the regular camera and photo import are available as alternatives."))
            .setNeutralButton(tr("Lisensi PDF", "PDF licenses")) { _, _ -> showPdfLicenses() }
            .setPositiveButton(tr("Mengerti", "OK"), null).show()
    }

    private fun showPdfLicenses() {
        val names = listOf("pdfbox-android-NOTICE.txt", "pdfbox-android-LICENSE.txt",
            "bouncy-castle-LICENSE.txt", "liberation-font-LICENSE.txt")
        val text = try {
            names.joinToString("\n\n--------------------\n\n") { name ->
                "$name\n\n" + assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        } catch (_: Exception) { tr("Teks lisensi tidak dapat dibaca.", "License text could not be read.") }
        AlertDialog.Builder(this).setTitle(tr("Lisensi komponen PDF", "PDF component licenses")).setMessage(text)
            .setPositiveButton(tr("Tutup", "Close"), null).show()
    }

    private fun refresh() {
        val version = ++refreshVersion
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { DocumentStore.list(applicationContext) }
            if (version != refreshVersion) return@launch
            documents = loaded
            documentCount.text = loaded.size.toString()
            totalPages.text = loaded.sumOf { it.pages.size }.toString()
            renderDocuments()
        }
    }

    private fun renderDocuments() {
        if (!::documentList.isInitialized) return
        val dp = { n: Int -> Ui.dp(this, n) }
        val onSurface = Ui.color(this, R.color.md_on_surface)
        val muted = Ui.color(this, R.color.md_on_surface_variant)
        val surface = Ui.color(this, R.color.md_surface_container_lowest)
        val primary = Ui.color(this, R.color.md_primary)
        val filtered = documents.filter { it.title.contains(query, ignoreCase = true) }
        sectionCount.text = tr("${filtered.size} dokumen", "${filtered.size} ${if (filtered.size == 1) "document" else "documents"}")
        documentList.removeAllViews()
        if (filtered.isEmpty()) {
            val empty = Ui.column(this).apply {
                gravity = Gravity.CENTER
                background = Ui.bg(surface, dp(18).toFloat(), Ui.color(context, R.color.md_outline_variant), dp(1))
                setPadding(dp(22), dp(29), dp(22), dp(29))
            }
            empty.addView(Ui.image(this, if (query.isEmpty()) R.drawable.ic_empty_doc else R.drawable.ic_search, primary).apply {
                background = Ui.bg(Ui.color(context, R.color.md_primary_container), dp(20).toFloat())
                setPadding(dp(15), dp(15), dp(15), dp(15))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            empty.addView(Ui.text(this, if (query.isEmpty()) tr("Belum ada dokumen", "No documents yet") else tr("Tidak ada hasil", "No results"), 16f, onSurface, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
            })
            empty.addView(Ui.text(this, if (query.isEmpty()) tr("Mulai pindai atau impor foto untuk membuat dokumen pertama Anda.", "Start scanning or import photos to create your first document.") else tr("Coba kata kunci yang berbeda.", "Try a different search term."), 13f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0)
            })
            documentList.addView(empty, LinearLayout.LayoutParams(-1, -2))
            return
        }
        filtered.forEach { doc ->
            val card = Ui.row(this).apply {
                background = Ui.bg(surface, dp(16).toFloat(), Ui.color(context, R.color.md_outline_variant), dp(1))
                setPadding(dp(12), dp(12), dp(4), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { openDocument(doc) }
            }
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.bg(Ui.color(context, R.color.md_surface_container), dp(8).toFloat())
                clipToOutline = true
                contentDescription = tr("Pratinjau ${doc.title}", "Preview ${doc.title}")
            }
            card.addView(preview, LinearLayout.LayoutParams(dp(56), dp(70)))
            loadThumbnail(preview, DocumentStore.pageFile(this, doc.id, doc.pages.first()))
            val info = Ui.column(this).apply { setPadding(dp(14), 0, 0, 0) }
            info.addView(Ui.text(this, doc.title, 15f, onSurface, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(Ui.text(this, "${Ui.pages(this, doc.pages.size)}  •  ${Ui.date(this, doc.updatedAt)}", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val more = Ui.iconButton(this, R.drawable.ic_more_vert, tr("Opsi ${doc.title}", "Options for ${doc.title}"))
            more.setOnClickListener { showDocumentMenu(more, doc) }
            card.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
            documentList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun openDocument(doc: Document) {
        startActivity(Intent(this, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_ID, doc.id))
    }

    private fun showDocumentMenu(anchor: View, doc: Document) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, tr("Buka", "Open"))
            menu.add(0, 2, 1, tr("Ubah nama", "Rename"))
            menu.add(0, 3, 2, tr("Hapus", "Delete"))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openDocument(doc)
                    2 -> DocumentDialogs.rename(this@MainActivity, doc.title) { title ->
                        updateDocument { DocumentStore.rename(applicationContext, doc.id, title) }
                    }
                    3 -> DocumentDialogs.delete(this@MainActivity, doc.title) {
                        updateDocument { DocumentStore.delete(applicationContext, doc.id) }
                    }
                }
                true
            }
            show()
        }
    }

    private fun updateDocument(action: () -> Unit) {
        busy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                busy(false)
                refresh()
            } catch (e: Exception) { busy(false); error(tr("Perubahan tidak dapat disimpan.", "Changes could not be saved."), e) }
        }
    }
}
