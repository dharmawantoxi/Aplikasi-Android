package com.dharmawantoxi.docscan

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.view.WindowCompat
import com.dharmawantoxi.docscan.ui.BaseActivity
import com.dharmawantoxi.docscan.ui.Ui

/** Android adaptation of the existing desktop splash, using the same logo and colours. */
class SplashActivity : BaseActivity() {
    companion object {
        private const val DURATION_MS = 3_000L
        private const val STATE_DEADLINE = "splash_deadline"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var animation: ValueAnimator? = null
    private lateinit var progress: ProgressBar
    private val openHome = Runnable {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = SystemClock.elapsedRealtime()
        val restored = savedInstanceState?.getLong(STATE_DEADLINE) ?: 0L
        deadline = if (restored in 1..now + DURATION_MS) restored else now + DURATION_MS
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(openHome)
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, DURATION_MS)
        if (remaining == 0L) {
            handler.post(openHome)
            return
        }
        val initialProgress = ((DURATION_MS - remaining) * 100 / DURATION_MS).toInt()
        progress.progress = initialProgress
        animation?.cancel()
        animation = ValueAnimator.ofInt(initialProgress, 100).apply {
            duration = remaining
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress.progress = it.animatedValue as Int }
            start()
        }
        handler.postDelayed(openHome, remaining)
    }

    override fun onPause() {
        handler.removeCallbacks(openHome)
        animation?.cancel()
        animation = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_DEADLINE, deadline)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openHome)
        animation?.cancel()
        super.onDestroy()
    }

    private fun buildScreen() {
        val dp = { n: Int -> Ui.dp(this, n) }
        val blue = 0xFF1E88E5.toInt()
        val root = FrameLayout(this).apply {
            background = Ui.bg(Color.BLACK, 0f, blue, dp(2))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val content = Ui.column(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val configuration = resources.configuration
        val logoDp = minOf(300, maxOf(96, minOf(configuration.screenWidthDp - 48, configuration.screenHeightDp - 230)))
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.splash_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo DharmawanToxi"
            tag = "splash_logo"
        }, LinearLayout.LayoutParams(dp(logoDp), dp(logoDp)))
        content.addView(Ui.text(this, getString(R.string.app_name), 19f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            tag = "splash_app_name"
        }, LinearLayout.LayoutParams(-1, -2))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        content.addView(Ui.text(this, if (version.isBlank()) "" else "Version $version", 13f, 0xFF888888.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Ui.text(this, tr("Memuat aplikasi...", "Loading app..."), 14f, blue).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(blue)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF1A1A1A.toInt())
            contentDescription = tr("Animasi pembuka", "Opening animation")
        }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        content.addView(Ui.text(this, "© 2026 DharmawanToxi. All rights reserved.", 11f, 0xFF888888.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setScreen(root)
        // BaseActivity follows the app theme; the splash is always dark.
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
