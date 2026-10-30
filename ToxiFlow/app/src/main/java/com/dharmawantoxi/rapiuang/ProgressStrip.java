package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A single progress bar or a two-color cash-flow comparison. */
final class ProgressStrip extends View {
    private final int percent;
    private final int foreground;
    private final int background;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ProgressStrip(Context context, int percent, int foreground, int background) {
        super(context);
        this.percent = Math.max(0, Math.min(100, percent));
        this.foreground = foreground;
        this.background = background;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), r = h / 2;
        paint.setColor(background);
        canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, paint);
        if (percent > 0) {
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setColor(foreground);
            canvas.drawRect(0, 0, w * percent / 100f, h, paint);
            canvas.restore();
        }
    }
}
