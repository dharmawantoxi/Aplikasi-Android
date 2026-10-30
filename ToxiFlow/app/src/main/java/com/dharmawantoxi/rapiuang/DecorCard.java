package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.FrameLayout;

/** A quiet, clipped motif keeps the balance card distinctive without external artwork. */
final class DecorCard extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    DecorCard(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackground(Ui.shape(context, Ui.DARK, 24));
        setClipToOutline(true);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(Color.argb(24, 205, 237, 210));
        float x = getWidth() * .94f;
        float y = Ui.dp(getContext(), 12);
        for (int r = 58; r <= 118; r += 15) canvas.drawCircle(x, y, Ui.dp(getContext(), r), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(80, 192, 229, 194));
        canvas.drawCircle(getWidth() - Ui.dp(getContext(), 28), Ui.dp(getContext(), 24), Ui.dp(getContext(), 2), paint);
    }
}
