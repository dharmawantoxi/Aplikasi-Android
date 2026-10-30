package com.dharmawantoxi.rapiuang;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Adapts the repository's DharmawanToxi splash for ToxiFlow. */
public final class SplashActivity extends Activity {
    static final long DURATION_MS = SplashTiming.DURATION_MS;
    private static final String STATE_DEADLINE = "splash_deadline";
    private static final int BLUE = 0xFF1E88E5;
    private static final int TRACK = 0xFF1A1A1A;
    private static final int SECONDARY_TEXT = 0xFF888888;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueAnimator animation;
    private ProgressBar progress;
    // Kept across recreation, so rotating the device does not restart the splash.
    long deadline;

    private final Runnable openHome = () -> {
        if (!isFinishing() && !isDestroyed()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    };

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageSettings.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long now = SystemClock.elapsedRealtime();
        long restored = savedInstanceState == null ? 0 : savedInstanceState.getLong(STATE_DEADLINE, 0);
        deadline = SplashTiming.deadline(now, restored);
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(openHome);
        long remaining = SplashTiming.remaining(SystemClock.elapsedRealtime(), deadline);
        if (remaining == 0) {
            handler.post(openHome);
            return;
        }
        int initial = SplashTiming.progress(remaining);
        progress.setProgress(initial);
        if (animation != null) animation.cancel();
        animation = ValueAnimator.ofInt(initial, 100);
        animation.setDuration(remaining);
        animation.setInterpolator(new LinearInterpolator());
        animation.addUpdateListener(value -> progress.setProgress((Integer) value.getAnimatedValue()));
        animation.start();
        handler.postDelayed(openHome, remaining);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(openHome);
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putLong(STATE_DEADLINE, deadline);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(openHome);
        if (animation != null) animation.cancel();
        super.onDestroy();
    }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.BLACK);
        frame.setStroke(Ui.dp(this, 2), BLUE);
        root.setBackground(frame);
        root.setTag("splash_root");
        setupDarkWindow(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        content.setGravity(Gravity.CENTER);
        Ui.pad(content, 24, 16, 24, 16);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        Configuration config = getResources().getConfiguration();
        int logoDp = Math.min(300, Math.max(96,
                Math.min(config.screenWidthDp - 48, config.screenHeightDp - 230)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription(getString(R.string.splash_logo_description));
        logo.setTag("splash_logo");
        content.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, logoDp), Ui.dp(this, logoDp)));

        TextView title = Ui.text(this, getString(R.string.app_name), 19, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setTag("splash_app_name");
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        String version = "";
        try {
            String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (installed != null && !installed.trim().isEmpty()) version = getString(R.string.splash_version, installed);
        } catch (PackageManager.NameNotFoundException ignored) {
            // The splash is still usable if package information is unavailable.
        }
        TextView versionLabel = Ui.text(this, version, 13, SECONDARY_TEXT, false);
        versionLabel.setGravity(Gravity.CENTER);
        versionLabel.setPadding(0, Ui.dp(this, 4), 0, 0);
        versionLabel.setTag("splash_version");
        content.addView(versionLabel, new LinearLayout.LayoutParams(-1, -2));

        TextView loading = Ui.text(this, getString(R.string.splash_loading), 14, BLUE, false);
        loading.setTag("splash_loading");
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        content.addView(loading, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setProgressTintList(ColorStateList.valueOf(BLUE));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(TRACK));
        progress.setContentDescription(getString(R.string.splash_progress_description));
        progress.setTag("splash_progress");
        content.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 8)));

        TextView copyright = Ui.text(this, getString(R.string.splash_copyright), 11, SECONDARY_TEXT, false);
        copyright.setGravity(Gravity.CENTER);
        copyright.setPadding(0, Ui.dp(this, 22), 0, 0);
        content.addView(copyright, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void setupDarkWindow(View root) {
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        // White system icons, unlike the light-themed main application.
        window.getDecorView().setSystemUiVisibility(0);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
    }
}
