package com.dharmawantoxi.docscan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.data.Document
import com.dharmawantoxi.docscan.data.DocumentNames
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.data.Exports
import com.dharmawantoxi.docscan.edit.PageEditActivity
import com.dharmawantoxi.docscan.edit.TextEditActivity
import com.dharmawantoxi.docscan.ocr.OcrActivity
import com.dharmawantoxi.docscan.scan.ScanHostActivity
import com.dharmawantoxi.docscan.ui.DocumentDialogs
import com.dharmawantoxi.docscan.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentActivity : ScanHostActivity() {
    companion object { const val EXTRA_ID = "document_id" }
    private lateinit var documentId: String
    private var document: Document? = null
    private var pendingJpegPage: String? = null
    private var refreshVersion = 0

    private val savePdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) exportPdf(uri)
    }
    private val saveJpegLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val page = pendingJpegPage
        pendingJpegPage = null
        if (uri != null && page != null) exportJpeg(page, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentId = intent.getStringExtra(EXTRA_ID).orEmpty()
        pendingJpegPage = savedInstanceState?.getString("pending_jpeg_page")
        if (documentId.isBlank()) { finish(); return }
        val loading = Ui.column(this).apply {
            setBackgroundColor(Ui.color(context, R.color.md_background))
            gravity = Gravity.CENTER
            addView(Ui.text(context, tr("Memuat dokumen…", "Loading document…"), 15f, Ui.color(context, R.color.md_on_surface_variant)))
        }
        setScreen(loading)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_jpeg_page", pendingJpegPage)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::documentId.isInitialized && documentId.isNotBlank()) refresh()
    }

    override fun availablePages(): Int {
        // Hasil pemilih bisa tiba sebelum onResume saat aktivitas dibuat ulang.
        val count = document?.pages?.size ?: DocumentStore.get(applicationContext, documentId)?.pages?.size
        return (DocumentStore.MAX_PAGES - (count ?: DocumentStore.MAX_PAGES)).coerceAtLeast(0)
    }

    override fun onImagesSelected(uris: List<Uri>) {
        busy(true, tr("Menambahkan halaman…", "Adding pages…"))
        lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { DocumentStore.addPages(applicationContext, documentId, uris) }
                busy(false)
                ++refreshVersion
                document = updated
                buildScreen(updated)
                toast(tr("Halaman berhasil ditambahkan.", "Pages added successfully."))
            } catch (e: Exception) {
                busy(false)
                error(tr("Halaman tidak dapat ditambahkan. Pastikan gambar dapat dibuka dan ruang penyimpanan cukup.", "Pages could not be added. Make sure the images can be opened and there is enough storage."), e)
            } finally {
                cleanCameraInputs(uris)
            }
        }
    }

    private fun refresh() {
        val version = ++refreshVersion
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { DocumentStore.get(applicationContext, documentId) }
            if (version != refreshVersion) return@launch
            if (loaded == null) {
                AlertDialog.Builder(this@DocumentActivity).setTitle(tr("Dokumen tidak ditemukan", "Document not found"))
                    .setMessage(tr("Dokumen mungkin sudah dihapus atau tidak dapat dibaca.", "The document may have been deleted or cannot be read."))
                    .setPositiveButton(tr("Kembali", "Back")) { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            } else {
                document = loaded
                buildScreen(loaded)
            }
        }
    }

    private fun buildScreen(doc: Document) {
        val c = this
        val dp = { n: Int -> Ui.dp(c, n) }
        val onSurface = Ui.color(c, R.color.md_on_surface)
        val muted = Ui.color(c, R.color.md_on_surface_variant)
        val primary = Ui.color(c, R.color.md_primary)
        val surface = Ui.color(c, R.color.md_surface_container_lowest)
        val outline = Ui.color(c, R.color.md_outline_variant)
        val root = Ui.column(c).apply { setBackgroundColor(Ui.color(c, R.color.md_background)) }

        val toolbar = Ui.row(c).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        toolbar.addView(Ui.iconButton(c, R.drawable.ic_arrow_back, tr("Kembali", "Back")).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = Ui.column(c).apply { setPadding(dp(4), 0, 0, 0) }
        heading.addView(Ui.text(c, tr("DOKUMEN", "DOCUMENT"), 10f, muted, true).apply { letterSpacing = 0.08f })
        heading.addView(Ui.text(c, doc.title, 17f, onSurface, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        toolbar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = Ui.iconButton(c, R.drawable.ic_more_vert, tr("Opsi dokumen", "Document options"))
        menuButton.setOnClickListener { showDocumentMenu(menuButton, doc) }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar)

        val scroll = ScrollView(c).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val content = Ui.column(c).apply { setPadding(dp(20), dp(14), dp(20), dp(32)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreen(root)

        val overview = Ui.column(c).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(22).toFloat())
            setPadding(dp(20), dp(19), dp(20), dp(20))
        }
        val upper = Ui.row(c)
        val icon = FrameLayout(c).apply { background = Ui.bg(primary, dp(14).toFloat()) }
        icon.addView(Ui.image(c, R.drawable.ic_pdf, Ui.color(c, R.color.md_on_primary)),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        upper.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
        val info = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        info.addView(Ui.text(c, tr("SIAP UNTUK DIBAGIKAN", "READY TO SHARE"), 10f, Ui.color(c, R.color.md_on_primary_container), true).apply { letterSpacing = 0.06f })
        info.addView(Ui.text(c, "${Ui.pages(this, doc.pages.size)}  •  ${Ui.date(this, doc.updatedAt)}", 13f, Ui.color(c, R.color.md_on_primary_container)).apply { setPadding(0, dp(4), 0, 0) })
        upper.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        overview.addView(upper)
        overview.addView(Ui.text(c, doc.title, 21f, Ui.color(c, R.color.md_on_primary_container), true).apply {
            setPadding(0, dp(16), 0, dp(17)); maxLines = 3
        })
        val exports = Ui.row(c)
        exports.addView(Ui.button(c, tr("Simpan PDF", "Save PDF"), R.drawable.ic_download).apply {
            setOnClickListener {
                try { savePdfLauncher.launch("${DocumentNames.fileName(doc.title)}.pdf") }
                catch (_: ActivityNotFoundException) { error(tr("Pemilih lokasi penyimpanan tidak tersedia.", "No save-location picker is available.")) }
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1.2f))
        exports.addView(Ui.button(c, tr("Bagikan", "Share"), R.drawable.ic_share, outlined = true).apply {
            setOnClickListener { sharePdf(doc.id) }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        overview.addView(exports)
        content.addView(overview)

        val actions = Ui.row(c).apply { setPadding(0, dp(14), 0, 0) }
        actions.addView(Ui.button(c, tr("Tambah", "Add"), R.drawable.ic_add, outlined = true).apply {
            setOnClickListener { showAddOptions() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(Ui.button(c, tr("Pindai teks", "Scan text"), R.drawable.ic_text, outlined = true).apply {
            setOnClickListener { startActivity(Intent(c, OcrActivity::class.java).putExtra(EXTRA_ID, doc.id)) }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        content.addView(actions)

        val listHeader = Ui.row(c).apply { setPadding(0, dp(27), 0, dp(5)) }
        listHeader.addView(Ui.text(c, tr("Halaman", "Pages"), 19f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        listHeader.addView(Ui.text(c, "${doc.pages.size} / ${DocumentStore.MAX_PAGES}", 12f, muted))
        content.addView(listHeader)
        content.addView(Ui.text(c, tr("Ketuk halaman untuk memotong, memutar, atau memberi filter. Tambahkan teks melalui tombol pada halaman.", "Tap a page to crop, rotate, or apply a filter. Use the button on a page to add text."), 13f, muted).apply {
            setPadding(0, 0, 0, dp(12))
        })
        doc.pages.forEachIndexed { index, page ->
            val card = Ui.row(c).apply {
                background = Ui.bg(surface, dp(16).toFloat(), outline, dp(1))
                setPadding(dp(12), dp(12), dp(5), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { editPage(page) }
            }
            val preview = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.bg(Ui.color(c, R.color.md_surface_container), dp(8).toFloat())
                clipToOutline = true
                contentDescription = tr("Pratinjau halaman ${index + 1}", "Preview page ${index + 1}")
            }
            card.addView(preview, LinearLayout.LayoutParams(dp(65), dp(82)))
            loadThumbnail(preview, DocumentStore.pageFile(c, doc.id, page))
            val pageText = Ui.column(c).apply { setPadding(dp(14), 0, 0, 0) }
            pageText.addView(Ui.text(c, tr("Halaman ${index + 1}", "Page ${index + 1}"), 15f, onSurface, true))
            pageText.addView(Ui.text(c, tr("Tambah teks", "Add text"), 13f, primary, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                contentDescription = tr("Tambah teks pada halaman ${index + 1}", "Add text to page ${index + 1}")
                setOnClickListener { addText(page) }
            }, LinearLayout.LayoutParams(-1, dp(44)))
            card.addView(pageText, LinearLayout.LayoutParams(0, -2, 1f))
            val more = Ui.iconButton(c, R.drawable.ic_more_vert, tr("Opsi halaman ${index + 1}", "Options for page ${index + 1}"))
            more.setOnClickListener { showPageMenu(more, doc, page, index) }
            card.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun editPage(page: String) {
        startActivity(Intent(this, PageEditActivity::class.java)
            .putExtra(EXTRA_ID, documentId).putExtra(PageEditActivity.EXTRA_PAGE, page))
    }

    private fun addText(page: String) {
        startActivity(Intent(this, TextEditActivity::class.java)
            .putExtra(EXTRA_ID, documentId).putExtra(PageEditActivity.EXTRA_PAGE, page))
    }

    private fun showDocumentMenu(anchor: View, doc: Document) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, tr("Ubah nama", "Rename"))
            menu.add(0, 2, 1, tr("Hapus dokumen", "Delete document"))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> DocumentDialogs.rename(this@DocumentActivity, doc.title) { name ->
                        update { DocumentStore.rename(applicationContext, documentId, name) }
                    }
                    2 -> DocumentDialogs.delete(this@DocumentActivity, doc.title) {
                        busy(true, tr("Menghapus dokumen…", "Deleting document…"))
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { DocumentStore.delete(applicationContext, documentId) }
                                busy(false); finish()
                            } catch (e: Exception) { busy(false); error(tr("Dokumen tidak dapat dihapus.", "The document could not be deleted."), e) }
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun showPageMenu(anchor: View, doc: Document, page: String, index: Int) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, tr("Edit halaman", "Edit page"))
            menu.add(0, 7, 1, tr("Tambah teks", "Add text"))
            menu.add(0, 2, 2, tr("Simpan sebagai JPG", "Save as JPG"))
            menu.add(0, 3, 3, tr("Bagikan JPG", "Share JPG"))
            menu.add(0, 4, 4, tr("Pindahkan ke atas", "Move up")).isEnabled = index > 0
            menu.add(0, 5, 5, tr("Pindahkan ke bawah", "Move down")).isEnabled = index < doc.pages.lastIndex
            menu.add(0, 6, 6, tr("Hapus halaman", "Delete page"))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> editPage(page)
                    7 -> addText(page)
                    2 -> {
                        pendingJpegPage = page
                        try { saveJpegLauncher.launch("${DocumentNames.fileName(doc.title)} - ${index + 1}.jpg") }
                        catch (_: ActivityNotFoundException) { pendingJpegPage = null; error(tr("Pemilih lokasi penyimpanan tidak tersedia.", "No save-location picker is available.")) }
                    }
                    3 -> shareJpeg(doc.id, page)
                    4 -> update { DocumentStore.movePage(applicationContext, doc.id, page, -1) }
                    5 -> update { DocumentStore.movePage(applicationContext, doc.id, page, 1) }
                    6 -> {
                        if (doc.pages.size == 1) {
                            AlertDialog.Builder(this@DocumentActivity).setMessage(tr("Dokumen harus memiliki setidaknya satu halaman. Untuk menghapus semuanya, pilih Hapus dokumen di menu atas.", "A document must have at least one page. To delete everything, choose Delete document from the top menu."))
                                .setPositiveButton(tr("Mengerti", "OK"), null).show()
                        } else {
                            AlertDialog.Builder(this@DocumentActivity).setTitle(tr("Hapus halaman ${index + 1}?", "Delete page ${index + 1}?"))
                                .setMessage(tr("Halaman ini akan dihapus dari dokumen.", "This page will be removed from the document."))
                                .setNegativeButton(tr("Batal", "Cancel"), null)
                                .setPositiveButton(tr("Hapus", "Delete")) { _, _ -> update { DocumentStore.deletePage(applicationContext, doc.id, page) } }.show()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun update(action: () -> Document) {
        busy(true)
        lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { action() }
                ++refreshVersion
                busy(false); document = updated; buildScreen(updated)
            } catch (e: Exception) { busy(false); error(tr("Perubahan tidak dapat disimpan.", "Changes could not be saved."), e) }
        }
    }

    private fun exportPdf(uri: Uri) {
        busy(true, tr("Membuat PDF…", "Creating PDF…"))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val doc = DocumentStore.get(applicationContext, documentId) ?: throw IllegalStateException(tr("Dokumen tidak ditemukan", "Document not found"))
                    Exports.savePdf(applicationContext, doc, uri)
                }
                busy(false); toast(tr("PDF berhasil disimpan.", "PDF saved successfully."))
            } catch (e: Exception) { busy(false); error(tr("PDF tidak dapat disimpan.", "The PDF could not be saved."), e) }
        }
    }

    private fun exportJpeg(page: String, uri: Uri) {
        busy(true, tr("Menyimpan JPG…", "Saving JPG…"))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val doc = DocumentStore.get(applicationContext, documentId) ?: throw IllegalStateException(tr("Dokumen tidak ditemukan", "Document not found"))
                    Exports.saveJpeg(applicationContext, doc, page, uri)
                }
                busy(false); toast(tr("Gambar JPG berhasil disimpan.", "JPG image saved successfully."))
            } catch (e: Exception) { busy(false); error(tr("Gambar tidak dapat disimpan.", "The image could not be saved."), e) }
        }
    }

    private fun sharePdf(id: String) {
        busy(true, tr("Menyiapkan PDF…", "Preparing PDF…"))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val doc = DocumentStore.get(applicationContext, id) ?: throw IllegalStateException(tr("Dokumen tidak ditemukan", "Document not found"))
                    Exports.preparePdf(applicationContext, doc) to doc.title
                }
                busy(false); Exports.share(this@DocumentActivity, result.first, "application/pdf", result.second)
            } catch (e: Exception) { busy(false); error(tr("PDF tidak dapat dibagikan.", "The PDF could not be shared."), e) }
        }
    }

    private fun shareJpeg(id: String, page: String) {
        busy(true, tr("Menyiapkan gambar…", "Preparing image…"))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val doc = DocumentStore.get(applicationContext, id) ?: throw IllegalStateException(tr("Dokumen tidak ditemukan", "Document not found"))
                    Exports.prepareJpeg(applicationContext, doc, page) to doc.title
                }
                busy(false); Exports.share(this@DocumentActivity, result.first, "image/jpeg", result.second)
            } catch (e: Exception) { busy(false); error(tr("Gambar tidak dapat dibagikan.", "The image could not be shared."), e) }
        }
    }
}
