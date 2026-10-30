package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** A local-only image viewer with both pinch/drag and button-driven zoom. */
final class PhotoZoomView extends ImageView {
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector detector;
    private Bitmap photo;
    private float baseScale = 1, scale = 1, lastX, lastY;
    private boolean dragging;

    PhotoZoomView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setContentDescription("Foto transaksi. Cubit atau gunakan tombol untuk memperbesar, lalu geser untuk melihat.");
        detector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector gesture) {
                zoom(gesture.getScaleFactor(), gesture.getFocusX(), gesture.getFocusY());
                return true;
            }
        });
    }

    void setPhoto(Bitmap bitmap) {
        photo = bitmap;
        setImageBitmap(bitmap);
        resetZoom();
    }

    void resetZoom() {
        if (photo == null || getWidth() <= 0 || getHeight() <= 0) return;
        baseScale = Math.min((float) getWidth() / photo.getWidth(), (float) getHeight() / photo.getHeight());
        scale = baseScale;
        transform.reset();
        transform.setScale(scale, scale);
        transform.postTranslate((getWidth() - photo.getWidth() * scale) / 2,
                (getHeight() - photo.getHeight() * scale) / 2);
        setImageMatrix(transform);
    }

    void zoomBy(float factor) { zoom(factor, getWidth() / 2f, getHeight() / 2f); }

    private void zoom(float factor, float x, float y) {
        if (photo == null || getWidth() <= 0 || getHeight() <= 0 || factor <= 0) return;
        float next = Math.max(baseScale, Math.min(baseScale * 5, scale * factor));
        transform.postScale(next / scale, next / scale, x, y);
        scale = next;
        constrain();
    }

    private void constrain() {
        RectF rect = new RectF(0, 0, photo.getWidth(), photo.getHeight());
        transform.mapRect(rect);
        float dx = rect.width() <= getWidth() ? (getWidth() - rect.width()) / 2 - rect.left
                : rect.left > 0 ? -rect.left : rect.right < getWidth() ? getWidth() - rect.right : 0;
        float dy = rect.height() <= getHeight() ? (getHeight() - rect.height()) / 2 - rect.top
                : rect.top > 0 ? -rect.top : rect.bottom < getHeight() ? getHeight() - rect.bottom : 0;
        transform.postTranslate(dx, dy);
        setImageMatrix(transform);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (photo == null) return false;
        detector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX(); lastY = event.getY(); dragging = true;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && event.getPointerCount() == 1 && !detector.isInProgress()) {
                    transform.postTranslate(event.getX() - lastX, event.getY() - lastY);
                    lastX = event.getX(); lastY = event.getY();
                    constrain();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                if (remaining < event.getPointerCount()) {
                    lastX = event.getX(remaining); lastY = event.getY(remaining); dragging = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
