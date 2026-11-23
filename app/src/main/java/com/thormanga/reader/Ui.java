package com.thormanga.reader;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the interface in code. */
final class Ui {
    static final int ACCENT = 0xFFE5484D;
    static final int BG = 0xFF0E0E12;
    static final int PANEL = 0xF0141419;
    static final int BTN = 0xFF262630;
    static final int BTN_PRESSED = 0xFF3A3A48;
    static final int SELECTED_ROW = 0xFF3A1E24;
    static final int TEXT = 0xFFF2F2F5;
    static final int DIM = 0xFF9A9AA8;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView label(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String text, View.OnClickListener l) {
        TextView t = label(c, 15, TEXT, true);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 10);
        t.setPadding(p, p, p, p);
        t.setMinHeight(dp(c, 46));
        t.setSingleLine();
        t.setBackground(buttonBg(c));
        t.setClickable(true);
        t.setFocusable(false);
        t.setOnClickListener(l);
        return t;
    }

    static Drawable buttonBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(c, BTN_PRESSED));
        s.addState(new int[]{android.R.attr.state_selected}, round(c, ACCENT));
        s.addState(new int[]{}, round(c, BTN));
        return s;
    }

    static GradientDrawable round(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, 12));
        return g;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weighted(Context c, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        int m = dp(c, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    static LinearLayout.LayoutParams wrap(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = dp(c, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    static LinearLayout.LayoutParams fullWidth(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = dp(c, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    @SuppressWarnings("deprecation")
    static Display displayOf(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) return a.getDisplay();
        return a.getWindowManager().getDefaultDisplay();
    }

    @SuppressWarnings("deprecation")
    static void immersive(Window w) {
        if (w == null) return;
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}
