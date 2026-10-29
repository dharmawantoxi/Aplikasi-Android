package com.dharmawantoxi.docscan.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.dharmawantoxi.docscan.ui.Ui
import com.dharmawantoxi.docscan.ui.Language
import kotlin.math.hypot
import kotlin.math.min

/** Preview dengan empat titik yang dapat digeser untuk koreksi perspektif. */
class CropView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        private set
    var filterMode: Int = 0
        set(value) { field = value; invalidate() }
    var onChanged: (() -> Unit)? = null
    private var corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    private val imageRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 2).toFloat()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt() }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 2).toFloat()
    }
    private var selectedCorner = -1

    init {
        contentDescription = Language.text(context, "Pratinjau potong. Geser empat sudut untuk memilih area dokumen.", "Crop preview. Drag the four corners to select the document area.")
        isFocusable = true
    }

    fun setImage(value: Bitmap) {
        bitmap = value
        resetCorners()
    }

    fun resetCorners() {
        corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        invalidate()
    }

    fun selection(): FloatArray = corners.copyOf()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val padding = Ui.dp(context, 20).toFloat()
        val availableWidth = (width - padding * 2).coerceAtLeast(1f)
        val availableHeight = (height - padding * 2).coerceAtLeast(1f)
        val scale = min(availableWidth / image.width, availableHeight / image.height)
        val w = image.width * scale
        val h = image.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        imagePaint.colorFilter = ImageEditor.colorMatrix(filterMode)?.let(::ColorMatrixColorFilter)
        canvas.drawBitmap(image, null, imageRect, imagePaint)
        val path = Path().apply {
            moveTo(x(0), y(0))
            for (i in 1..3) lineTo(x(i), y(i))
            close()
        }
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imageRect, Path.Direction.CW)
            addPath(path)
        }
        canvas.drawPath(outside, shadePaint)
        canvas.drawPath(path, linePaint)
        for (i in 0..3) {
            canvas.drawCircle(x(i), y(i), Ui.dp(context, 8).toFloat(), handlePaint)
            canvas.drawCircle(x(i), y(i), Ui.dp(context, 8).toFloat(), handleBorder)
        }
    }

    private fun x(i: Int) = imageRect.left + corners[i * 2] * imageRect.width()
    private fun y(i: Int) = imageRect.top + corners[i * 2 + 1] * imageRect.height()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.width() <= 0 || imageRect.height() <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val closest = (0..3).minByOrNull { hypot(event.x - x(it), event.y - y(it)) } ?: return false
                if (hypot(event.x - x(closest), event.y - y(closest)) > Ui.dp(context, 38)) return false
                selectedCorner = closest
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectedCorner < 0) return false
                val next = corners.copyOf()
                next[selectedCorner * 2] = ((event.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                next[selectedCorner * 2 + 1] = ((event.y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                if (CropGeometry.valid(next)) {
                    corners = next
                    onChanged?.invoke()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedCorner = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
