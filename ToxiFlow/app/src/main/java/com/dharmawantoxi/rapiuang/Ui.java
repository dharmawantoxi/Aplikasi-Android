package com.dharmawantoxi.rapiuang;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(247, 248, 244);
    static final int WHITE = Color.WHITE;
    static final int INK = Color.rgb(26, 52, 43);
    static final int MUTED = Color.rgb(112, 129, 119);
    static final int GREEN = Color.rgb(31, 90, 72);
    static final int DARK = Color.rgb(22, 59, 48);
    static final int MINT = Color.rgb(201, 231, 202);
    static final int PALE = Color.rgb(235, 244, 234);
    static final int LINE = Color.rgb(229, 235, 227);
    static final int CORAL = Color.rgb(189, 106, 82);
    static final int CORAL_PALE = Color.rgb(253, 238, 231);

    private Ui() { }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String text, float size, int color, boolean medium) {
        TextView result = new TextView(c);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        result.setIncludeFontPadding(true);
        return result;
    }

    static TextView display(Context c, String text, float size) {
        TextView result = text(c, text, size, INK, false);
        result.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        result.setLineSpacing(dp(c, 1), 1f);
        return result;
    }

    static TextView overline(Context c, String value, int color) {
        TextView result = text(c, value, 10, color, true);
        result.setLetterSpacing(0.11f);
        return result;
    }

    static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout.LayoutParams weighted(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    static GradientDrawable shape(Context c, int color, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(c, radius));
        return background;
    }

    static GradientDrawable outlined(Context c, int color, int stroke, float radius) {
        GradientDrawable background = shape(c, color, radius);
        background.setStroke(dp(c, 1), stroke);
        return background;
    }

    static void clickable(View v, android.graphics.drawable.Drawable background, View.OnClickListener click) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x220E573F), background, null));
        v.setOnClickListener(click);
        v.setFocusable(true);
    }

    static View gap(Context c, int heightDp) {
        View gap = new View(c);
        gap.setLayoutParams(lp(1, dp(c, heightDp)));
        return gap;
    }

    static View divider(Context c) {
        View line = new View(c);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(lp(-1, dp(c, 1)));
        return line;
    }

    static FrameLayout iconBox(Context c, String icon, int iconColor, int background, int sizeDp, int iconDp, float radiusDp) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(shape(c, background, radiusDp));
        IconView image = new IconView(c, icon, iconColor);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER);
        box.addView(image, iconParams);
        box.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        return box;
    }

    static void pad(View view, int left, int top, int right, int bottom) {
        Context c = view.getContext();
        view.setPadding(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
    }

    static void setupWindow(Activity activity, View root) {
        Window window = activity.getWindow();
        window.setStatusBarColor(BG);
        // Android 7 cannot draw dark navigation icons, so keep its navigation bar dark.
        window.setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? WHITE : DARK);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 enforces edge-to-edge for target 35. Include the keyboard on forms.
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return insets;
            });
            root.requestApplyInsets();
        }
    }

    static String categoryIcon(String category) {
        switch (category) {
            case "Makan & minum": return "food";
            case "Belanja": return "bag";
            case "Transportasi": return "car";
            case "Tagihan": return "receipt";
            case "Kesehatan": return "heart";
            case "Hiburan": return "play";
            case "Pendidikan": return "book";
            case "Gaji": return "briefcase";
            case "Usaha": return "store";
            case "Hadiah": return "gift";
            case "Investasi": return "trend";
            default: return "dots";
        }
    }

    static int categoryColor(String category) {
        switch (category) {
            case "Makan & minum": return 0xffc27a50;
            case "Belanja": return 0xff94754c;
            case "Transportasi": return 0xff59809b;
            case "Tagihan": return 0xffa16c77;
            case "Kesehatan": return 0xffad6b73;
            case "Hiburan": return 0xff8772a4;
            case "Pendidikan": return 0xff66867c;
            case "Gaji": return 0xff4d8669;
            case "Usaha": return 0xff699276;
            case "Hadiah": return 0xffc18464;
            case "Investasi": return 0xff6681a0;
            default: return 0xff7d8d7b;
        }
    }

    static int categoryBackground(String category) {
        switch (category) {
            case "Makan & minum": return 0xfffff0e5;
            case "Belanja": return 0xfff6efdf;
            case "Transportasi": return 0xffe8f2f7;
            case "Tagihan": return 0xfff8e9eb;
            case "Kesehatan": return 0xfffaeaec;
            case "Hiburan": return 0xfff1ebf8;
            case "Pendidikan": return 0xffe8f3ed;
            case "Gaji": return 0xffe3f2e6;
            case "Usaha": return 0xffe9f3e7;
            case "Hadiah": return 0xffffeee5;
            case "Investasi": return 0xffe9eff7;
            default: return 0xffedf1eb;
        }
    }
}
