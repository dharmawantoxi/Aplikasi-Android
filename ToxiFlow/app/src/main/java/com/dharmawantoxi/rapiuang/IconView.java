package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small hand-drawn line icons; no icon font or network assets are required. */
final class IconView extends View {
    private final String icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    IconView(Context context, String icon, int color) {
        super(context);
        this.icon = icon;
        paint.setColor(color);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, paint); }
    private void round(Canvas c, float l, float t, float r, float b, float rad) { c.drawRoundRect(l, t, r, b, rad, rad, paint); }
    private void path(Canvas c, float... p) {
        Path path = new Path();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i + 1 < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        c.drawPath(path, paint);
    }
    private void dot(Canvas c, float x, float y, float radius) {
        Paint.Style style = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, radius, paint);
        paint.setStyle(style);
    }

    @Override protected void onDraw(Canvas original) {
        super.onDraw(original);
        Canvas c = original;
        int save = c.save();
        float side = Math.min(getWidth(), getHeight());
        c.translate((getWidth() - side) / 2f, (getHeight() - side) / 2f);
        c.scale(side / 24f, side / 24f);
        switch (icon) {
            case "wallet":
                round(c, 3.5f, 6, 20.5f, 19, 2.4f); path(c, 4, 7, 4, 5.5f, 17, 3.5f, 18, 6);
                round(c, 14, 10, 21, 15, 1.5f); dot(c, 17, 12.5f, .8f); break;
            case "home":
                path(c, 3, 10.5f, 12, 3.5f, 21, 10.5f); path(c, 5.5f, 9.5f, 5.5f, 20, 18.5f, 20, 18.5f, 9.5f);
                path(c, 10, 20, 10, 14, 14, 14, 14, 20); break;
            case "list":
                line(c, 8, 6, 20, 6); line(c, 8, 12, 20, 12); line(c, 8, 18, 20, 18);
                dot(c, 4, 6, 1.1f); dot(c, 4, 12, 1.1f); dot(c, 4, 18, 1.1f); break;
            case "chart":
                line(c, 4, 20, 20, 20); round(c, 5, 11, 8, 19, 1); round(c, 10.5f, 6.5f, 13.5f, 19, 1); round(c, 16, 3, 19, 19, 1); break;
            case "plus": line(c, 12, 4, 12, 20); line(c, 4, 12, 20, 12); break;
            case "back": path(c, 14.5f, 5, 7.5f, 12, 14.5f, 19); break;
            case "next": path(c, 9.5f, 5, 16.5f, 12, 9.5f, 19); break;
            case "down": path(c, 6, 9, 12, 15, 18, 9); break;
            case "income": path(c, 18, 6, 6, 18, 6, 10); path(c, 6, 18, 14, 18); break;
            case "expense": path(c, 6, 18, 18, 6, 10, 6); path(c, 18, 6, 18, 14); break;
            case "calendar":
                round(c, 4, 5, 20, 20, 2); line(c, 4, 9, 20, 9); line(c, 8, 3, 8, 7); line(c, 16, 3, 16, 7);
                dot(c, 8, 13, .8f); dot(c, 12, 13, .8f); dot(c, 16, 13, .8f); dot(c, 8, 17, .8f); dot(c, 12, 17, .8f); break;
            case "search": c.drawCircle(10.5f, 10.5f, 6, paint); line(c, 15, 15, 20, 20); break;
            case "info": c.drawCircle(12, 12, 9, paint); line(c, 12, 11, 12, 16); dot(c, 12, 7.5f, 1); break;
            case "eye":
                Path eye = new Path(); eye.moveTo(2, 12); eye.cubicTo(6, 5, 18, 5, 22, 12); eye.cubicTo(18, 19, 6, 19, 2, 12); c.drawPath(eye, paint); c.drawCircle(12, 12, 2.8f, paint); break;
            case "eyeoff":
                Path eo = new Path(); eo.moveTo(2, 12); eo.cubicTo(6, 5, 18, 5, 22, 12); eo.cubicTo(18, 19, 6, 19, 2, 12); c.drawPath(eo, paint); c.drawCircle(12, 12, 2.8f, paint); line(c, 4, 4, 20, 20); break;
            case "close": line(c, 5, 5, 19, 19); line(c, 19, 5, 5, 19); break;
            case "food":
                line(c, 6, 3, 6, 11); line(c, 3.5f, 3, 3.5f, 8); line(c, 8.5f, 3, 8.5f, 8);
                path(c, 3.5f, 8, 4.5f, 10, 6, 11, 7.5f, 10, 8.5f, 8); line(c, 6, 11, 6, 21);
                c.drawOval(14, 3, 20, 12, paint); line(c, 17, 12, 17, 21); break;
            case "bag":
                round(c, 4, 8, 20, 20, 2); Path handle = new Path(); handle.moveTo(8, 9); handle.lineTo(8, 7); handle.cubicTo(8, 2, 16, 2, 16, 7); handle.lineTo(16, 9); c.drawPath(handle, paint); break;
            case "car":
                round(c, 4, 7, 20, 17, 2); path(c, 6, 7, 7.5f, 4, 16.5f, 4, 18, 7); line(c, 4, 12, 20, 12);
                line(c, 6, 17, 6, 19); line(c, 18, 17, 18, 19); dot(c, 7.5f, 14.5f, .8f); dot(c, 16.5f, 14.5f, .8f); break;
            case "receipt":
                path(c, 6, 21, 6, 4, 18, 4, 18, 21, 15, 19, 12, 21, 9, 19, 6, 21); line(c, 9, 9, 15, 9); line(c, 9, 13, 15, 13); break;
            case "heart":
                Path heart = new Path(); heart.moveTo(12, 20); heart.cubicTo(10, 18, 3, 13.5f, 3, 8.5f); heart.cubicTo(3, 4, 8.5f, 2.5f, 12, 7); heart.cubicTo(15.5f, 2.5f, 21, 4, 21, 8.5f); heart.cubicTo(21, 13.5f, 14, 18, 12, 20); c.drawPath(heart, paint); break;
            case "play": c.drawCircle(12, 12, 8.5f, paint); path(c, 10, 8, 16, 12, 10, 16, 10, 8); break;
            case "book":
                path(c, 12, 19, 12, 6, 10, 5, 4, 5, 4, 18, 10, 18, 12, 19); path(c, 12, 6, 14, 5, 20, 5, 20, 18, 14, 18, 12, 19); break;
            case "briefcase":
                round(c, 3.5f, 7, 20.5f, 19, 2); path(c, 8, 7, 8, 4, 16, 4, 16, 7); line(c, 3.5f, 12, 20.5f, 12); line(c, 11, 12, 13, 12); break;
            case "store":
                path(c, 4, 9, 5, 4, 19, 4, 20, 9); path(c, 4, 9, 4, 11, 6, 12, 8, 10, 10, 12, 12, 10, 14, 12, 16, 10, 18, 12, 20, 11, 20, 9);
                path(c, 5, 12, 5, 20, 19, 20, 19, 12); path(c, 10, 20, 10, 15, 14, 15, 14, 20); break;
            case "gift":
                round(c, 4, 9, 20, 20, 1); round(c, 3, 7, 21, 10, 1); line(c, 12, 7, 12, 20);
                Path ribbon = new Path(); ribbon.moveTo(12, 7); ribbon.cubicTo(5, 7, 5, 2, 8, 3); ribbon.cubicTo(10, 3, 12, 7, 12, 7); ribbon.cubicTo(12, 7, 14, 3, 16, 3); ribbon.cubicTo(19, 2, 19, 7, 12, 7); c.drawPath(ribbon, paint); break;
            case "trend": path(c, 3, 17, 9, 11, 13, 14, 20, 6); path(c, 15, 6, 20, 6, 20, 11); break;
            case "dots": dot(c, 6, 12, 1.5f); dot(c, 12, 12, 1.5f); dot(c, 18, 12, 1.5f); break;
            case "lock": round(c, 5, 10, 19, 20, 2); Path lock = new Path(); lock.moveTo(8, 10); lock.lineTo(8, 7); lock.cubicTo(8, 2, 16, 2, 16, 7); lock.lineTo(16, 10); c.drawPath(lock, paint); dot(c, 12, 15, 1); break;
            case "edit": path(c, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20); line(c, 14, 6, 18, 10); break;
            case "trash": line(c, 4, 6, 20, 6); path(c, 7, 6, 8, 20, 16, 20, 17, 6); path(c, 9, 6, 9, 3, 15, 3, 15, 6); line(c, 10, 10, 10, 16); line(c, 14, 10, 14, 16); break;
            default: c.drawCircle(12, 12, 8, paint); break;
        }
        c.restoreToCount(save);
    }
}
