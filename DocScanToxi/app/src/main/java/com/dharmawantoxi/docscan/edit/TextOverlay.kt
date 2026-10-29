package com.dharmawantoxi.docscan.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.dharmawantoxi.docscan.ui.Ui
import com.dharmawantoxi.docscan.ui.Language
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Koordinat dan ukuran relatif, sehingga hasil sesuai dengan pratinjau. */
data class TextOverlaySpec(
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val sizeFraction: Float = 0.06f,
    val color: Int = Color.BLACK
)

object TextOverlayRenderer {
    data class Layout(val bounds: RectF, val baseline: Float, val paint: Paint, val margin: Float)

    fun layout(width: Int, height: Int, spec: TextOverlaySpec): Layout {
        require(width > 0 && height > 0)
        val margin = min(width, height) * 0.02f
        val availableWidth = (width - 2f * margin).coerceAtLeast(1f)
        val availableHeight = (height - 2f * margin).coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.color
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = (width * spec.sizeFraction.coerceIn(0.02f, 0.15f)).coerceAtLeast(1f)
        }
        val textWidth = paint.measureText(spec.text).coerceAtLeast(0.001f)
        val fontHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(0.001f)
        val fit = min(1f, min(availableWidth / textWidth, availableHeight / fontHeight))
        paint.textSize *= fit
        val finalWidth = paint.measureText(spec.text)
        val metrics = paint.fontMetrics
        val finalHeight = metrics.descent - metrics.ascent
        val left = margin + spec.x.coerceIn(0f, 1f) * (availableWidth - finalWidth).coerceAtLeast(0f)
        val top = margin + spec.y.coerceIn(0f, 1f) * (availableHeight - finalHeight).coerceAtLeast(0f)
        return Layout(RectF(left, top, left + finalWidth, top + finalHeight), top - metrics.ascent, paint, margin)
    }

    fun draw(canvas: Canvas, width: Int, height: Int, spec: TextOverlaySpec): Layout {
        val layout = layout(width, height, spec)
        canvas.drawText(spec.text, layout.bounds.left, layout.baseline, layout.paint)
        return layout
    }

    fun render(source: Bitmap, spec: TextOverlaySpec): Bitmap {
        require(spec.text.isNotBlank()) { "Teks tidak boleh kosong" }
        val result = source.copy(Bitmap.Config.ARGB_8888, true) ?: throw IOException("Tidak dapat membuat gambar")
        try {
            draw(Canvas(result), result.width, result.height, spec)
            return result
        } catch (e: Exception) {
            result.recycle()
            throw e
        }
    }
}

/** Ketuk/geser teks; posisi juga dapat dipilih lewat tombol Posisi untuk aksesibilitas. */
class TextOverlayView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        private set
    var spec = TextOverlaySpec("")
        set(value) { field = value; invalidate() }
    var onPositionChanged: ((Float, Float) -> Unit)? = null
    private val imageRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E88E5.toInt()
        style = Paint.Style.STROKE
    }
    private var moving = false

    init {
        contentDescription = Language.text(context, "Pratinjau teks. Ketuk atau geser pada halaman untuk memindahkan teks.", "Text preview. Tap or drag on the page to move the text.")
        isFocusable = true
    }

    fun setImage(image: Bitmap) { bitmap = image; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val pad = Ui.dp(context, 16).toFloat()
        val availableWidth = (width - 2f * pad).coerceAtLeast(1f)
        val availableHeight = (height - 2f * pad).coerceAtLeast(1f)
        val scale = min(availableWidth / image.width, availableHeight / image.height)
        val w = image.width * scale
        val h = image.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(image, null, imageRect, imagePaint)
        val preview = if (spec.text.isBlank()) spec.copy(text = Language.text(context, "Teks", "Text")) else spec
        canvas.save()
        canvas.translate(imageRect.left, imageRect.top)
        canvas.scale(scale, scale)
        val layout = TextOverlayRenderer.draw(canvas, image.width, image.height, preview)
        borderPaint.strokeWidth = Ui.dp(context, 1).toFloat() / scale
        val gap = Ui.dp(context, 3).toFloat() / scale
        canvas.drawRect(layout.bounds.left - gap, layout.bounds.top - gap,
            layout.bounds.right + gap, layout.bounds.bottom + gap, borderPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                moving = true
                parent?.requestDisallowInterceptTouchEvent(true)
                moveTo(event, image)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moving) return false
                moveTo(event, image)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moving) return false
                moving = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveTo(event: MotionEvent, image: Bitmap) {
        val preview = if (spec.text.isBlank()) spec.copy(text = Language.text(context, "Teks", "Text")) else spec
        val layout = TextOverlayRenderer.layout(image.width, image.height, preview)
        val sourceX = (event.x - imageRect.left) * image.width / imageRect.width()
        val sourceY = (event.y - imageRect.top) * image.height / imageRect.height()
        val spanX = max(0f, image.width - 2f * layout.margin - layout.bounds.width())
        val spanY = max(0f, image.height - 2f * layout.margin - layout.bounds.height())
        val x = if (spanX == 0f) 0.5f else ((sourceX - layout.margin - layout.bounds.width() / 2f) / spanX).coerceIn(0f, 1f)
        val y = if (spanY == 0f) 0.5f else ((sourceY - layout.margin - layout.bounds.height() / 2f) / spanY).coerceIn(0f, 1f)
        spec = spec.copy(x = x, y = y)
        onPositionChanged?.invoke(x, y)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
