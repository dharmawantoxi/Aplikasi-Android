package com.dharmawantoxi.docscan.scan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.dharmawantoxi.docscan.data.DocumentStore
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Language
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

/** Pemindai otomatis + alternatif kamera sistem dan pemilih foto. */
abstract class ScanHostActivity : BaseActivity() {
    private var pendingCameraPath: String? = null
    private var startingScanner = false

    protected abstract fun availablePages(): Int
    protected abstract fun onImagesSelected(uris: List<Uri>)

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            val uris = pages.map { it.imageUri }
            if (uris.isNotEmpty()) receive(uris) else error(tr("Tidak ada halaman yang diterima dari pemindai.", "No pages were received from the scanner."))
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) receive(uris)
    }

    private val cameraLauncher = registerForActivityResult(CaptureImage()) { success ->
        val file = pendingCameraPath?.let(::File)
        pendingCameraPath = null
        if (success && file != null && file.isFile && file.length() > 0) {
            // Uri file hanya digunakan di dalam aplikasi, tidak dikirim ke aplikasi lain.
            receive(listOf(Uri.fromFile(file)))
        } else {
            file?.delete()
            if (success) error(tr("Foto dari kamera kosong. Coba lagi atau impor foto dari galeri.", "The camera photo is empty. Try again or import a photo from the gallery."))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraPath = savedInstanceState?.getString("pending_camera_path")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_camera_path", pendingCameraPath)
        super.onSaveInstanceState(outState)
    }

    private fun receive(uris: List<Uri>) {
        val remaining = availablePages().coerceIn(0, DocumentStore.MAX_PAGES)
        if (remaining == 0) {
            cleanCameraInputs(uris)
            toast(tr("Maksimal ${DocumentStore.MAX_PAGES} halaman per dokumen.", "Maximum ${DocumentStore.MAX_PAGES} pages per document."))
            return
        }
        if (uris.size > remaining) toast(tr("Hanya $remaining halaman pertama yang ditambahkan.", "Only the first $remaining pages were added."))
        onImagesSelected(uris.take(remaining))
    }

    /** Panggil setelah impor selesai, bukan sebelum foto selesai dibaca. */
    protected fun cleanCameraInputs(uris: List<Uri>) {
        val dir = File(cacheDir, "camera").canonicalFile
        uris.filter { it.scheme == "file" }.forEach { uri ->
            runCatching {
                val file = File(requireNotNull(uri.path)).canonicalFile
                if (file.parentFile == dir) file.delete()
            }
        }
    }

    protected fun showAddOptions() {
        if (availablePages() <= 0) {
            toast(tr("Maksimal ${DocumentStore.MAX_PAGES} halaman per dokumen.", "Maximum ${DocumentStore.MAX_PAGES} pages per document."))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Tambahkan halaman", "Add pages"))
            .setItems(arrayOf(tr("Pindai otomatis", "Automatic scan"), tr("Ambil foto dengan kamera", "Take a photo"), tr("Impor foto", "Import photos"))) { _, which ->
                when (which) { 0 -> startScanner(); 1 -> startCamera(); 2 -> startGallery() }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null).show()
    }

    protected fun startScanner() {
        val remaining = availablePages().coerceIn(0, DocumentStore.MAX_PAGES)
        if (remaining == 0) { toast(tr("Maksimal ${DocumentStore.MAX_PAGES} halaman per dokumen.", "Maximum ${DocumentStore.MAX_PAGES} pages per document.")); return }
        if (startingScanner) return
        startingScanner = true
        busy(true, tr("Menyiapkan pemindai…", "Preparing scanner…"))
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(remaining)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    startingScanner = false
                    busy(false)
                    if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@addOnSuccessListener
                    try {
                        scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } catch (_: Exception) { scannerUnavailable() }
                }
                .addOnFailureListener {
                    startingScanner = false
                    busy(false)
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) scannerUnavailable()
                }
        } catch (_: Exception) {
            startingScanner = false
            busy(false)
            scannerUnavailable()
        }
    }

    private fun scannerUnavailable() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(tr("Pemindai otomatis belum tersedia", "Automatic scanning is unavailable"))
            .setMessage(tr("Fitur ini memerlukan Google Play Services dan mungkin perlu mengunduh komponennya saat pertama digunakan. Anda tetap dapat memakai kamera biasa atau mengimpor foto.", "This feature requires Google Play Services and may need to download its component the first time. You can still use the camera or import photos."))
            .setPositiveButton(tr("Kamera biasa", "Camera")) { _, _ -> startCamera() }
            .setNeutralButton(tr("Impor foto", "Import photos")) { _, _ -> startGallery() }
            .setNegativeButton(tr("Batal", "Cancel"), null).show()
    }

    protected fun startGallery() {
        if (availablePages() <= 0) { toast(tr("Dokumen sudah penuh.", "The document is full.")); return }
        try { galleryLauncher.launch(arrayOf("image/*")) }
        catch (_: ActivityNotFoundException) { error(tr("Tidak ada pemilih foto di perangkat ini.", "No photo picker is available on this device.")) }
    }

    protected fun startCamera() {
        if (availablePages() <= 0) { toast(tr("Dokumen sudah penuh.", "The document is full.")); return }
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            dir.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 2 * 24 * 60 * 60 * 1000L }
                .forEach { it.delete() }
            val file = File.createTempFile("photo-", ".jpg", dir)
            pendingCameraPath = file.absolutePath
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            pendingCameraPath?.let { File(it).delete() }
            pendingCameraPath = null
            error(tr("Kamera tidak dapat dibuka. Coba impor foto dari galeri.", "The camera could not be opened. Try importing a photo from the gallery."), e)
        }
    }

    private class CaptureImage : ActivityResultContract<Uri, Boolean>() {
        override fun createIntent(context: Context, input: Uri): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newUri(context.contentResolver, Language.text(context, "Foto dokumen", "Document photo"), input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
    }
}
