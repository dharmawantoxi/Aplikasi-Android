package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SplashActivityTest {
    private static final String TEST_DB = "rapiuang-ui-test.db";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context;
    private LanguageTestPreference testLanguage;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        testLanguage = new LanguageTestPreference(context, AppLanguage.INDONESIAN);
        context.deleteDatabase(TEST_DB);
        FinanceStore.useDatabaseForUiTests(context, true);
    }

    @After public void tearDown() {
        if (testLanguage != null) testLanguage.close();
        FinanceStore.useDatabaseForUiTests(context, false);
        context.deleteDatabase(TEST_DB);
    }

    @Test public void existingLauncherComponentOpensSplash() {
        Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launcher);
        // Keeping this component also keeps existing pinned launcher shortcuts valid.
        assertEquals(MainActivity.class.getName(), launcher.getComponent().getClassName());
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(SplashActivity.class.getName(), null, false);
        Activity splash = null;
        try {
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(launcher)) {
                splash = instrumentation.waitForMonitorWithTimeout(monitor, 8_000);
                assertNotNull("Launcher tidak membuka splash", splash);
            }
        } finally {
            finish(splash);
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void showsExistingLogoAndVersionThenOpensHomeAfterRecreation() throws Exception {
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        Activity home = null;
        long[] firstDeadline = new long[1];
        try {
            try (ActivityScenario<SplashActivity> scenario = ActivityScenario.launch(SplashActivity.class)) {
                scenario.onActivity(activity -> {
                    View decor = activity.getWindow().getDecorView();
                    ImageView logo = decor.findViewWithTag("splash_logo");
                    assertNotNull(logo);
                    assertNotNull(logo.getDrawable());
                    assertEquals(activity.getString(R.string.splash_logo_description), logo.getContentDescription().toString());
                    TextView title = decor.findViewWithTag("splash_app_name");
                    assertNotNull(title);
                    assertEquals(activity.getString(R.string.app_name), title.getText().toString());
                    TextView version = decor.findViewWithTag("splash_version");
                    assertNotNull(version);
                    assertEquals(activity.getString(R.string.splash_version, versionName), version.getText().toString());
                    ProgressBar progress = decor.findViewWithTag("splash_progress");
                    assertNotNull(progress);
                    assertEquals(100, progress.getMax());
                    // Leave enough time for recreation on a slow emulator.
                    activity.deadline = SystemClock.elapsedRealtime() + SplashActivity.DURATION_MS;
                    firstDeadline[0] = activity.deadline;
                });
                scenario.recreate();
                scenario.onActivity(activity -> assertEquals(firstDeadline[0], activity.deadline));
                home = instrumentation.waitForMonitorWithTimeout(monitor, 8_000);
                assertNotNull("Beranda tidak terbuka setelah splash", home);
            }
        } finally {
            finish(home);
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void pausedSplashWaitsUntilResumedBeforeOpeningHome() {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        Activity home = null;
        try {
            try (ActivityScenario<SplashActivity> scenario = ActivityScenario.launch(SplashActivity.class)) {
                // The original callback remains due at 3 seconds, but leave a margin
                // for the scenario to observe RESUMED before the splash finishes.
                scenario.onActivity(activity -> activity.deadline = SystemClock.elapsedRealtime()
                        + SplashActivity.DURATION_MS * 2);
                scenario.moveToState(Lifecycle.State.CREATED);
                assertNull("Splash membuka beranda saat di latar belakang",
                        monitor.waitForActivityWithTimeout(SplashActivity.DURATION_MS + 500));
                scenario.moveToState(Lifecycle.State.RESUMED);
                home = instrumentation.waitForMonitorWithTimeout(monitor, 8_000);
                assertNotNull("Beranda tidak terbuka setelah kembali ke splash", home);
            }
        } finally {
            finish(home);
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void backCancelsPendingNavigation() {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            try (ActivityScenario<SplashActivity> scenario = ActivityScenario.launch(SplashActivity.class)) {
                scenario.onActivity(Activity::onBackPressed);
                assertNull("Splash yang ditutup tidak boleh membuka beranda",
                        instrumentation.waitForMonitorWithTimeout(monitor, SplashActivity.DURATION_MS + 500));
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void finish(Activity activity) {
        if (activity != null) {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }
}
