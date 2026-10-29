package com.dharmawantoxi.docscan

import android.app.Activity
import android.content.Intent
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplashActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun launcherShowsExistingLogoThenOpensHomeAfterRecreation() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launch)
        assertEquals(SplashActivity::class.java.name, launch!!.component?.className)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var opened: Activity? = null
        try {
            ActivityScenario.launch<SplashActivity>(Intent(context, SplashActivity::class.java)).use { splash ->
                splash.onActivity { activity ->
                    val logo = activity.window.decorView.findViewWithTag<ImageView>("splash_logo")
                    assertNotNull(logo)
                    assertNotNull(logo.drawable)
                    val title = activity.window.decorView.findViewWithTag<TextView>("splash_app_name")
                    assertEquals(context.getString(R.string.app_name), title.text.toString())
                }
                splash.recreate()
                opened = instrumentation.waitForMonitorWithTimeout(monitor, 8_000)
                assertNotNull("Beranda tidak terbuka setelah splash", opened)
            }
        } finally {
            opened?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }
}
