package com.thormanga.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

/**
 * Draws pages.
 * MAIN is the upper screen region, SUB continues it on the lower screen (split / strip modes),
 * PREVIEW is the small "next page" thumbnail on the control screen.
 * Everything is laid out in inches so a page lines up physically across two screens
 * with different pixel densities.
 */
final class PageView extends View implements Session.Listener {
    static final int MAIN = 0, SUB = 1, PREVIEW = 2;
    private static final int SCROLL_NONE = 0, SCROLL_STRIP = 1, SCROLL_PAN = 2;

    final int role;
    final float ppi;
    private final Session s;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint phPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private final OverScroller scroller;
    private int scrollMode = SCROLL_NONE;

    // Page-mode zoom state
    private float zScale = -1, panX, panY, zAspect;
    private int zPage = -1, zW, zH, lastTw;
    private boolean zUser, zFit, scaling;

    PageView(Context c, int role) {
        super(c);
        this.role = role;
        s = Session.get(c);
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float p = (m.xdpi + m.ydpi) / 2f;
        if (p < m.densityDpi * 0.6f || p > m.densityDpi * 1.6f) p = m.densityDpi;
        ppi = p;
        phPaint.setColor(0xFF1C1C24);
        txtPaint.setColor(Ui.DIM);
        txtPaint.setTextAlign(Paint.Align.CENTER);
        txtPaint.setTextSize(Ui.dp(c, 16));
        gestures = new GestureDetector(c, new Gestures());
        scaler = new ScaleGestureDetector(c, new Zoom());
        scroller = new OverScroller(c);
        if (role != PREVIEW) setBackgroundColor(0xFF000000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        s.addListener(this);
        if (role == MAIN) s.mainView = this;
        else if (role == SUB) s.subView = this;
        s.geometryChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        s.removeListener(this);
        if (s.mainView == this) s.mainView = null;
        if (s.subView == this) s.subView = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        zScale = -1;
        if (role != PREVIEW) s.geometryChanged();
    }

    @Override
    public void onSessionChanged() {
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (s.book == null || getWidth() == 0 || getHeight() == 0) return;
        if (role == PREVIEW) drawPreview(c);
        else if (s.mode == Session.Mode.PAGE) { if (role == MAIN) drawPage(c); }
        else if (s.mode == Session.Mode.SPLIT) drawSplit(c);
        else drawStrip(c);
    }

    private float regionTop() {
        return role == MAIN ? 0f : s.regionH(s.mainView);
    }

    private void drawSplit(Canvas c) {
        float[] p = s.splitPlace();
        int tw = Math.round(p[0] * s.maxPpi());
        drawAt(c, s.page, p[0], p[1] - regionTop(), p[2], tw);
        if (role == MAIN) {
            s.prefetch(s.page + 1, tw);
            s.prefetch(s.page + 2, tw);
            s.prefetch(s.page - 1, tw);
        }
    }

    private void drawStrip(Canvas c) {
        float top = s.stripOffset() + regionTop();
        float bottom = top + getHeight() / ppi;
        float cw = s.colW();
        int tw = Math.round(cw * s.maxPpi());
        float[] y = s.stripYs();
        int n = Math.min(s.count(), y.length - 1);
        int i = s.stripIndexAt(top);
        for (; i < n && y[i] < bottom; i++) drawAt(c, i, cw, y[i] - top, y[i + 1] - y[i], tw);
        for (int k = 0; k < 2 && i + k < n; k++) s.prefetch(i + k, tw);
    }

    /** Draws a page {@code wU} inches wide, centred horizontally, {@code topU} inches from the view top. */
    private void drawAt(Canvas c, int pg, float wU, float topU, float hU, int tw) {
        float w = wU * ppi, h = hU * ppi;
        float left = (getWidth() - w) / 2f, top = topU * ppi;
        dst.set(left, top, left + w, top + h);
        if (dst.bottom < 0 || dst.top > getHeight()) return;
        drawBitmapOrPlaceholder(c, pg, tw);
    }

    private void drawBitmapOrPlaceholder(Canvas c, int pg, int tw) {
        Bitmap b = s.bitmap(pg, tw);
        if (b != null && !b.isRecycled()) {
            c.drawBitmap(b, null, dst, bmpPaint);
        } else {
            c.drawRect(dst, phPaint);
            float cy = Math.max(dst.top, 0) + Math.min(dst.height(), getHeight()) / 2f;
            String msg = s.failed(pg, tw) ? "Page " + (pg + 1) + " couldn't be loaded" : "Page " + (pg + 1);
            c.drawText(msg, dst.centerX(), cy, txtPaint);
        }
    }

    private void drawPage(Canvas c) {
        int pg = s.page;
        float a = s.aspect(pg);
        int w = getWidth(), h = getHeight();
        if (zPage != pg || zScale < 0 || zW != w || zH != h || zFit != s.fitWidth || (!zUser && a != zAspect)) {
            resetZoom(pg, a);
        }
        float cw = zScale, ch = zScale * a;
        clampPan(cw, ch);
        dst.set(panX, panY, panX + cw, panY + ch);
        int tw = scaling && lastTw > 0 ? lastTw : Math.min(Math.round(cw), 4096);
        lastTw = tw;
        drawBitmapOrPlaceholder(c, pg, tw);
        int fitTw = Math.round(s.fitWidth ? w : Math.min(w, h / a));
        s.prefetch(pg + 1, fitTw);
        s.prefetch(pg - 1, fitTw);
    }

    private void resetZoom(int pg, float a) {
        int w = getWidth(), h = getHeight();
        zPage = pg;
        zW = w;
        zH = h;
        zAspect = a;
        zFit = s.fitWidth;
        zUser = false;
        zScale = s.fitWidth ? w : Math.min(w, h / a);
        float ch = zScale * a;
        panX = (w - zScale) / 2f;
        panY = ch < h ? (h - ch) / 2f : (s.enterAtEnd ? h - ch : 0);
        s.enterAtEnd = false;
    }

    private void clampPan(float cw, float ch) {
        int w = getWidth(), h = getHeight();
        panX = cw <= w ? (w - cw) / 2f : Math.max(w - cw, Math.min(0, panX));
        panY = ch <= h ? (h - ch) / 2f : Math.max(h - ch, Math.min(0, panY));
    }

    private void drawPreview(Canvas c) {
        int pg = s.page + 1;
        if (pg >= s.count()) {
            c.drawText("End of book", getWidth() / 2f, getHeight() / 2f, txtPaint);
            return;
        }
        float a = s.aspect(pg);
        float pw = Math.min(getWidth(), getHeight() / a), ph = pw * a;
        float left = (getWidth() - pw) / 2f, top = (getHeight() - ph) / 2f;
        dst.set(left, top, left + pw, top + ph);
        drawBitmapOrPlaceholder(c, pg, Math.round(pw));
    }

    /** Page mode: scroll a tall page by most of a screen. Returns false at the edge. */
    boolean scrollStep(int dir) {
        if (s.mode != Session.Mode.PAGE || zScale < 0) return false;
        int h = getHeight();
        float ch = zScale * s.aspect(s.page);
        if (ch <= h + 1) return false;
        float min = h - ch;
        if (dir > 0 && panY <= min + 1) return false;
        if (dir < 0 && panY >= -1) return false;
        float target = Math.max(min, Math.min(0, panY - dir * h * 0.8f));
        scroller.forceFinished(true);
        scroller.startScroll(Math.round(panX), Math.round(panY), 0, Math.round(target - panY), 220);
        scrollMode = SCROLL_PAN;
        postInvalidateOnAnimation();
        return true;
    }

    @Override
    public void computeScroll() {
        if (scrollMode != SCROLL_NONE && scroller.computeScrollOffset()) {
            if (scrollMode == SCROLL_STRIP) {
                s.setStripOffset(scroller.getCurrY() / 1000f);
            } else {
                panX = scroller.getCurrX();
                panY = scroller.getCurrY();
                invalidate();
            }
            postInvalidateOnAnimation();
        } else {
            scrollMode = SCROLL_NONE;
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    private void tap(float x) {
        if (s.mode == Session.Mode.STRIP) {
            s.toggleOverlay();
            return;
        }
        float f = x / getWidth();
        if (f < 0.3f) s.left();
        else if (f > 0.7f) s.right();
        else s.toggleOverlay();
    }

    private class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            if (!scroller.isFinished()) scroller.forceFinished(true);
            scrollMode = SCROLL_NONE;
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (role == PREVIEW) {
                s.next();
                return true;
            }
            if (s.mode != Session.Mode.PAGE) {
                tap(e.getX());
                return true;
            }
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (role != PREVIEW && s.mode == Session.Mode.PAGE) tap(e.getX());
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (role == PREVIEW) s.next();
            else if (s.mode == Session.Mode.PAGE) s.toggleFit();
            else tap(e.getX()); // rapid taps still turn pages
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (role == PREVIEW) return false;
            if (s.mode == Session.Mode.STRIP) {
                s.scrollStrip(dy / ppi);
                return true;
            }
            if (s.mode == Session.Mode.PAGE && role == MAIN && zScale > 0) {
                float ch = zScale * s.aspect(s.page);
                if (zScale > getWidth() + 1 || ch > getHeight() + 1) {
                    panX -= dx;
                    panY -= dy;
                    invalidate();
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (role == PREVIEW) return false;
            if (s.mode == Session.Mode.STRIP) {
                scroller.fling(0, Math.round(s.stripOffset() * 1000), 0, Math.round(-vy / ppi * 1000),
                        0, 0, 0, Math.round(s.stripMax() * 1000));
                scrollMode = SCROLL_STRIP;
                postInvalidateOnAnimation();
                return true;
            }
            boolean pagePannableX = s.mode == Session.Mode.PAGE && zScale > getWidth() + 1;
            if (Math.abs(vx) > Math.abs(vy) * 1.3f && Math.abs(vx) > 600 && !pagePannableX) {
                if (vx < 0) s.right(); else s.left();
                return true;
            }
            if (s.mode == Session.Mode.SPLIT && Math.abs(vy) > Math.abs(vx) * 1.3f && Math.abs(vy) > 800) {
                if (vy < 0) s.next(); else s.prev();
                return true;
            }
            if (s.mode == Session.Mode.PAGE && role == MAIN && zScale > 0) {
                float cw = zScale, ch = zScale * s.aspect(s.page);
                int minX = Math.round(Math.min(0, getWidth() - cw)), minY = Math.round(Math.min(0, getHeight() - ch));
                scroller.fling(Math.round(panX), Math.round(panY), Math.round(vx), Math.round(vy),
                        minX, cw > getWidth() ? 0 : minX, minY, ch > getHeight() ? 0 : minY);
                scrollMode = SCROLL_PAN;
                postInvalidateOnAnimation();
                return true;
            }
            return false;
        }
    }

    private class Zoom extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            scaling = role == MAIN && s.mode == Session.Mode.PAGE && zScale > 0;
            return scaling;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            float a = s.aspect(s.page);
            float fit = Math.min(getWidth(), getHeight() / a);
            float ns = Math.max(fit * 0.8f, Math.min(getWidth() * 4f, zScale * d.getScaleFactor()));
            float f = ns / zScale;
            panX = d.getFocusX() - (d.getFocusX() - panX) * f;
            panY = d.getFocusY() - (d.getFocusY() - panY) * f;
            zScale = ns;
            zUser = true;
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            scaling = false;
            invalidate();
        }
    }
}
