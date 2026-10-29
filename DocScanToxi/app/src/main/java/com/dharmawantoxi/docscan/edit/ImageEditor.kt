package com.dharmawantoxi.docscan.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageEditor {
    fun rotate(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { setRotate(90f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun colorMatrix(mode: Int): ColorMatrix? = when (mode) {
        1 -> ColorMatrix().apply { setSaturation(0f) }
        2 -> ColorMatrix().apply {
            setSaturation(0f)
            val contrast = 1.8f
            val offset = 128f * (1f - contrast)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        else -> null
    }

    /** Mengubah segi empat sumber menjadi persegi panjang dan menerapkan filter. */
    fun crop(source: Bitmap, points: FloatArray, filterMode: Int): Bitmap {
        require(CropGeometry.valid(points)) { "Area potong tidak valid" }
        val src = FloatArray(8) { index -> points[index] * if (index % 2 == 0) source.width else source.height }
        fun distance(a: Int, b: Int): Float = hypot(src[a * 2] - src[b * 2], src[a * 2 + 1] - src[b * 2 + 1])
        val rawWidth = max(distance(0, 1), distance(3, 2))
        val rawHeight = max(distance(0, 3), distance(1, 2))
        val scale = min(1f, 2400f / max(rawWidth, rawHeight))
        val width = (rawWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (rawHeight * scale).roundToInt().coerceAtLeast(1)
        require(width >= 16 && height >= 16) { "Area potong terlalu kecil" }
        val dst = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val transform = Matrix()
        require(transform.setPolyToPoly(src, 0, dst, 0, 4)) { "Area potong tidak dapat diproses" }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = colorMatrix(filterMode)?.let(::ColorMatrixColorFilter)
        }
        canvas.drawBitmap(source, transform, paint)
        return result
    }
}
