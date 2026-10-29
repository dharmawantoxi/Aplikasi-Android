package com.dharmawantoxi.docscan.data

import kotlin.math.min

data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Geometri murni, dapat diuji tanpa emulator. */
object PdfGeometry {
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
