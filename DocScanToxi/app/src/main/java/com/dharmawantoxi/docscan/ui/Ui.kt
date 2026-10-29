package com.dharmawantoxi.docscan.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.dharmawantoxi.docscan.R
import com.dharmawantoxi.docscan.data.ImageFiles
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object Ui {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    fun bg(@ColorInt color: Int, radius: Float, @ColorInt stroke: Int? = null, strokeWidth: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null) setStroke(strokeWidth, stroke)
        }

    fun gradient(@ColorInt start: Int, @ColorInt end: Int, radius: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply { cornerRadius = radius }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(context: Context, value: String, size: Float, @ColorInt color: Int,
             bold: Boolean = false): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = true
    }

    fun image(context: Context, @DrawableRes icon: Int, @ColorInt color: Int): ImageView = ImageView(context).apply {
        setImageDrawable(AppCompatResources.getDrawable(context, icon)?.mutate()?.apply { setTint(color) })
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    fun iconButton(context: Context, @DrawableRes icon: Int, description: String,
                   @ColorInt color: Int = Ui.color(context, R.color.md_on_surface)): ImageButton = ImageButton(context).apply {
        setImageDrawable(AppCompatResources.getDrawable(context, icon)?.mutate()?.apply { setTint(color) })
        contentDescription = description
        setBackgroundResource(android.R.color.transparent)
        val p = dp(context, 12)
        setPadding(p, p, p, p)
        minimumWidth = dp(context, 48)
        minimumHeight = dp(context, 48)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    fun button(context: Context, label: String, @DrawableRes icon: Int? = null,
               outlined: Boolean = false, onDark: Boolean = false): MaterialButton = MaterialButton(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        cornerRadius = dp(context, 14)
        minHeight = dp(context, 50)
        insetTop = 0
        insetBottom = 0
        val foreground = when {
            onDark -> Color.WHITE
            outlined -> color(context, R.color.md_primary)
            else -> color(context, R.color.md_on_primary)
        }
        val background = when {
            onDark -> Color.TRANSPARENT
            outlined -> Color.TRANSPARENT
            else -> color(context, R.color.md_primary)
        }
        backgroundTintList = ColorStateList.valueOf(background)
        setTextColor(foreground)
        iconTint = ColorStateList.valueOf(foreground)
        if (icon != null) setIconResource(icon)
        iconSize = dp(context, 19)
        iconPadding = dp(context, 8)
        if (outlined || onDark) {
            strokeWidth = dp(context, 1)
            strokeColor = ColorStateList.valueOf(if (onDark) 0x88FFFFFF.toInt() else color(context, R.color.md_outline_variant))
        }
    }

    fun date(context: Context, time: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag(Language.code(context))).format(Date(time))
    fun pages(context: Context, count: Int) = Language.text(context, "$count halaman", "$count ${if (count == 1) "page" else "pages"}")
}

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
    }

    protected fun tr(indonesian: String, english: String): String = Language.text(this, indonesian, english)
    protected fun detail(message: String?): String? = Language.detail(this, message)
    private var progress: AlertDialog? = null

    /** Menghindari konten tertutup status/navigation bar, termasuk Android 15+. */
    protected fun setScreen(root: View) {
        // Pastikan decor view sudah dibuat sebelum meminta WindowInsetsController
        // (PhoneWindow.getInsetsController dapat crash pada Android 11+ jika belum ada).
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, root)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(initialLeft + bars.left, initialTop + bars.top,
                initialRight + bars.right, initialBottom + max(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    protected fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    protected fun error(message: String, error: Throwable? = null) {
        if (isFinishing || isDestroyed) return
        val detail = detail(error?.localizedMessage)?.takeIf { it.isNotBlank() }
        AlertDialog.Builder(this).setTitle(tr("Belum berhasil", "Unable to complete"))
            .setMessage(if (detail == null) message else "$message\n\n$detail")
            .setPositiveButton(tr("Mengerti", "OK"), null).show()
    }

    protected fun busy(show: Boolean, message: String = tr("Memproses dokumen…", "Processing document…")) {
        progress?.dismiss()
        progress = null
        if (show && !isFinishing && !isDestroyed) {
            val row = Ui.row(this).apply {
                setPadding(Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24))
                addView(CircularProgressIndicator(context).apply { isIndeterminate = true },
                    LinearLayout.LayoutParams(Ui.dp(context, 32), Ui.dp(context, 32)))
                addView(Ui.text(context, message, 15f, Ui.color(context, R.color.md_on_surface)).apply {
                    setPadding(Ui.dp(context, 20), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            progress = AlertDialog.Builder(this).setView(row).setCancelable(false).create().also { it.show() }
        }
    }

    protected fun loadThumbnail(image: ImageView, file: File) {
        val tag = file.absolutePath + ":" + file.lastModified()
        image.tag = tag
        image.setImageResource(R.drawable.ic_empty_doc)
        image.imageTintList = ColorStateList.valueOf(Ui.color(this, R.color.md_primary))
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { ImageFiles.thumbnail(file) }
            if (image.tag == tag && bitmap != null) {
                image.imageTintList = null
                image.setImageBitmap(bitmap)
            }
        }
    }

    override fun onDestroy() {
        progress?.dismiss()
        progress = null
        super.onDestroy()
    }
}
