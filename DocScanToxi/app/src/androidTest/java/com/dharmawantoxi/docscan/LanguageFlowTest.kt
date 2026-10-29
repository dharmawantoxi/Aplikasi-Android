package com.dharmawantoxi.docscan

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dharmawantoxi.docscan.ui.Language
import com.dharmawantoxi.docscan.zip.ZipActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun languagePickerPersistsAndAppliesToOtherScreens() {
        val previous = Language.code(context)
        try {
            Language.set(context, Language.INDONESIAN)
            ActivityScenario.launch(MainActivity::class.java).use { home ->
                home.onActivity { activity ->
                    val logo = findDescription(activity.window.decorView, "Ikon aplikasi DocScan Toxi") as? ImageView
                    assertNotNull(logo)
                    assertNotNull(logo!!.drawable)
                    val languageButton = findDescription(activity.window.decorView, "Bahasa / Language")
                    assertNotNull(languageButton)
                    assertTrue(languageButton!!.performClick())
                }
                val option = waitForNode("English")
                assertNotNull("English option should be visible", option)
                var target: AccessibilityNodeInfo? = option
                var clicked = false
                while (target != null && !clicked) {
                    clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    target = target.parent
                }
                assertTrue("English option should be clickable", clicked)
                assertTrue("English home screen should appear", waitForText(home, "My documents"))
                assertEquals(Language.ENGLISH, Language.code(context))
                home.recreate()
                assertTrue("English should survive recreation", waitForText(home, "My documents"))
                home.onActivity { activity ->
                    assertEquals("en", activity.resources.configuration.locales[0].language)
                }
                ActivityScenario.launch<ZipActivity>(Intent(context, ZipActivity::class.java)).use { zip ->
                    assertTrue("Other screens should use English", waitForText(zip, "Create ZIP"))
                }
            }
        } finally { Language.set(context, previous) }
    }

    private fun findDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findDescription(view.getChildAt(i), description)?.let { return it }
        }
        return null
    }

    private fun hasText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
    }

    private fun <T : android.app.Activity> waitForText(scenario: ActivityScenario<T>, text: String): Boolean {
        val end = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < end) {
            var found = false
            scenario.onActivity { found = hasText(it.window.decorView, text) }
            if (found) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun waitForNode(text: String): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < end) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val result = root?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
            if (result != null) return result
            Thread.sleep(100)
        }
        return null
    }
}
