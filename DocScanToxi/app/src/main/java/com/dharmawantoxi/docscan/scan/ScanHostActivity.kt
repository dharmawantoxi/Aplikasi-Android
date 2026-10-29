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
import com.google.android.gms.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.android.gms.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.android.gms.mlkit.vision.documentscanner.GmsDocumentScanningResult
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
            if (uris.isNotEmpty()) receive(uris) else error("Tidak ada halaman yang diterima dari pemindai.")
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
            if (success) error("Foto dari kamera kosong. Coba lagi atau impor foto dari galeri.")
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
            toast("Maksimal ${DocumentStore.MAX_PAGES} halaman per dokumen.")
            return
        }
        if (uris.size > remaining) toast("Hanya $remaining halaman pertama yang ditambahkan.")
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
            toast("Maksimal ${DocumentStore.MAX_PAGES} halaman per dokumen.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Tambahkan halaman")
            .setItems(arrayOf("Pindai otomatis", "Ambil foto dengan kamera", "Impor foto")) { _, which ->
                when (which) { 0 -> startScanner(); 1 -> startCamera(); 2 -> startGallery() }
            }
            .setNegativeButton("Batal", null).show()
    }

    protected fun startScanner() {
        val remaining = availablePages().coerceIn(0, DocumentStore.MAX_PAGES)
        if (remaining == 0) { toast("Maksimal ${DocumentStore.MAX_PAGES} halaman per dokumen."); return }
        if (startingScanner) return
        startingScanner = true
        busy(true, "Menyiapkan pemindai…")
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(remaining)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener(this) { sender ->
                    startingScanner = false
                    busy(false)
                    try {
                        scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } catch (e: Exception) { scannerUnavailable() }
                }
                .addOnFailureListener(this) {
                    startingScanner = false
                    busy(false)
                    scannerUnavailable()
                }
        } catch (_: Exception) {
            startingScanner = false
            busy(false)
            scannerUnavailable()
        }
    }

    private fun scannerUnavailable() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Pemindai otomatis belum tersedia")
            .setMessage("Fitur ini memerlukan Google Play Services dan mungkin perlu mengunduh komponennya saat pertama digunakan. Anda tetap dapat memakai kamera biasa atau mengimpor foto.")
            .setPositiveButton("Kamera biasa") { _, _ -> startCamera() }
            .setNeutralButton("Impor foto") { _, _ -> startGallery() }
            .setNegativeButton("Batal", null).show()
    }

    protected fun startGallery() {
        if (availablePages() <= 0) { toast("Dokumen sudah penuh."); return }
        try { galleryLauncher.launch(arrayOf("image/*")) }
        catch (_: ActivityNotFoundException) { error("Tidak ada pemilih foto di perangkat ini.") }
    }

    protected fun startCamera() {
        if (availablePages() <= 0) { toast("Dokumen sudah penuh."); return }
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
            error("Kamera tidak dapat dibuka. Coba impor foto dari galeri.", e)
        }
    }

    private class CaptureImage : ActivityResultContract<Uri, Boolean>() {
        override fun createIntent(context: Context, input: Uri): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newUri(context.contentResolver, "Foto dokumen", input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
    }
}
