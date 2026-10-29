package com.dharmawantoxi.docscan.data

import kotlin.math.min
import kotlin.math.roundToInt

data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Geometri murni, dapat diuji tanpa emulator. */
object PdfGeometry {
    /** Ukuran fisik setelah rotasi atau potong halaman hasil impor PDF. */
    fun editedPageFormat(format: PdfPageFormat, oldWidth: Int, oldHeight: Int,
                         newWidth: Int, newHeight: Int): PdfPageFormat {
        require(oldWidth > 0 && oldHeight > 0 && newWidth > 0 && newHeight > 0)
        // Teks/filter tidak mengubah dimensi. Rotasi penuh cukup menukar ukuran,
        // termasuk ketika dimensi gambar dibulatkan pada halaman yang sangat sempit.
        if (newWidth == oldWidth && newHeight == oldHeight) return format
        if (newWidth == oldHeight && newHeight == oldWidth) return PdfPageFormat(format.height, format.width)
        // Sisi terpanjang tidak dibulatkan saat impor dan memberi skala terakurat.
        val pointsPerPixel = if (oldWidth >= oldHeight) format.width.toDouble() / oldWidth
            else format.height.toDouble() / oldHeight
        return PdfPageFormat(
            (newWidth * pointsPerPixel).roundToInt().coerceIn(1, PdfPageFormat.MAX_POINTS),
            (newHeight * pointsPerPixel).roundToInt().coerceIn(1, PdfPageFormat.MAX_POINTS)
        )
    }

    fun fit(width: Int, height: Int): PageRect {
        require(width > 0 && height > 0)
        val pageWidth = 595f
        val pageHeight = 842f
        val margin = 24f
        val scale = min((pageWidth - margin * 2) / width, (pageHeight - margin * 2) / height)
        val w = width * scale
        val h = height * scale
        return PageRect((pageWidth - w) / 2, (pageHeight - h) / 2,
            (pageWidth + w) / 2, (pageHeight + h) / 2)
    }
}
