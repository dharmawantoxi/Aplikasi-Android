package com.dharmawantoxi.docscan

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
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.data.Document
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.scan.ScanHostActivity
import com.dharmawantoxi.docscan.ui.DocumentDialogs
import com.dharmawantoxi.docscan.ui.Ui
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
        busy(true, "Menyimpan halaman…")
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { DocumentStore.create(applicationContext, uris) }
                busy(false)
                startActivity(Intent(this@MainActivity, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_ID, doc.id))
            } catch (e: Exception) {
                busy(false)
                error("Dokumen tidak dapat disimpan. Pastikan foto dapat dibuka dan ruang penyimpanan cukup.", e)
            } finally {
                cleanCameraInputs(uris)
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
        val brand = FrameLayout(c).apply { background = Ui.bg(primary, dp(14).toFloat()) }
        brand.addView(Ui.image(c, R.drawable.ic_scan, Color.WHITE), FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
        header.addView(brand, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brandText = Ui.column(c).apply { setPadding(dp(12), 0, 0, 0) }
        brandText.addView(Ui.text(c, "DocScan Toxi", 19f, onSurface, true))
        brandText.addView(Ui.text(c, "Dokumen rapi, kapan saja.", 12f, muted))
        header.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.iconButton(c, R.drawable.ic_info, "Tentang aplikasi").apply {
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
        heroText.addView(Ui.text(c, "RUANG DOKUMEN DIGITAL", 10f, 0xFFB7D8FF.toInt(), true).apply { letterSpacing = 0.08f })
        heroText.addView(Ui.text(c, "Pindai mudah,\nsimpan rapi.", 25f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(5)); setLineSpacing(0f, 1.03f)
        })
        heroText.addView(Ui.text(c, "Ubah lembaran jadi dokumen siap dibagikan.", 13f, 0xFFE1EDFF.toInt()).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f))
        heroRow.addView(makePaperIllustration(), LinearLayout.LayoutParams(dp(84), dp(110)).apply { leftMargin = dp(8) })
        hero.addView(heroRow)
        val heroActions = Ui.row(c).apply { setPadding(0, dp(18), 0, 0) }
        val scan = Ui.button(c, "Mulai pindai", R.drawable.ic_scan).apply {
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(0xFF124F98.toInt())
            iconTint = ColorStateList.valueOf(0xFF124F98.toInt())
            setOnClickListener { startScanner() }
        }
        heroActions.addView(scan, LinearLayout.LayoutParams(0, dp(50), 1.25f))
        heroActions.addView(Ui.button(c, "Impor foto", R.drawable.ic_image, onDark = true).apply {
            setOnClickListener { startGallery() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        hero.addView(heroActions)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val stats = Ui.row(c).apply {
            background = Ui.bg(surface, dp(18).toFloat(), Ui.color(c, R.color.md_outline_variant), dp(1))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val docStat = Ui.column(c)
        documentCount = Ui.text(c, "0", 20f, onSurface, true)
        docStat.addView(documentCount)
        docStat.addView(Ui.text(c, "Dokumen", 12f, muted))
        stats.addView(docStat, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(View(c).apply { setBackgroundColor(Ui.color(c, R.color.md_outline_variant)) },
            LinearLayout.LayoutParams(dp(1), dp(34)))
        val pageStat = Ui.column(c).apply { setPadding(dp(20), 0, 0, 0) }
        totalPages = Ui.text(c, "0", 20f, onSurface, true)
        pageStat.addView(totalPages)
        pageStat.addView(Ui.text(c, "Halaman", 12f, muted))
        stats.addView(pageStat, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(Ui.image(c, R.drawable.ic_layers, primary).apply {
            background = Ui.bg(Ui.color(c, R.color.md_primary_container), dp(12).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Disimpan di perangkat"
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val listHeader = Ui.row(c).apply { setPadding(0, dp(25), 0, dp(12)) }
        listHeader.addView(Ui.text(c, "Dokumen saya", 19f, onSurface, true), LinearLayout.LayoutParams(0, -2, 1f))
        sectionCount = Ui.text(c, "0 dokumen", 12f, muted)
        listHeader.addView(sectionCount)
        content.addView(listHeader)
        val search = EditText(c).apply {
            hint = "Cari nama dokumen"
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

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("Tentang DocScan Toxi")
            .setMessage("Pindai dan simpan dokumen sebagai PDF atau gambar, lalu bagikan bila diperlukan.\n\nDokumen disimpan di perangkat ini dan akan terhapus jika aplikasi dihapus. Simpan salinan melalui tombol ekspor.\n\nPemindai otomatis memerlukan Google Play Services; kamera biasa dan impor foto tersedia sebagai alternatif.")
            .setPositiveButton("Mengerti", null).show()
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
        sectionCount.text = "${filtered.size} dokumen"
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
            empty.addView(Ui.text(this, if (query.isEmpty()) "Belum ada dokumen" else "Tidak ada hasil", 16f, onSurface, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
            })
            empty.addView(Ui.text(this, if (query.isEmpty()) "Mulai pindai atau impor foto untuk membuat dokumen pertama Anda." else "Coba kata kunci yang berbeda.", 13f, muted).apply {
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
                contentDescription = "Pratinjau ${doc.title}"
            }
            card.addView(preview, LinearLayout.LayoutParams(dp(56), dp(70)))
            loadThumbnail(preview, DocumentStore.pageFile(this, doc.id, doc.pages.first()))
            val info = Ui.column(this).apply { setPadding(dp(14), 0, 0, 0) }
            info.addView(Ui.text(this, doc.title, 15f, onSurface, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(Ui.text(this, "${Ui.pages(doc.pages.size)}  •  ${Ui.date(doc.updatedAt)}", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val more = Ui.iconButton(this, R.drawable.ic_more_vert, "Opsi ${doc.title}")
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
            menu.add(0, 1, 0, "Buka")
            menu.add(0, 2, 1, "Ubah nama")
            menu.add(0, 3, 2, "Hapus")
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
            } catch (e: Exception) { busy(false); error("Perubahan tidak dapat disimpan.", e) }
        }
    }
}
