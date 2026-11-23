package com.thormanga.reader;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.FrameLayout;

/** One physical screen the app is drawing on (an activity window or a presentation). */
final class ScreenHost {
    final Activity activity;
    final Context ctx;
    final FrameLayout root;
    final Display display;

    ScreenHost(Activity activity, Context ctx, FrameLayout root, Display display) {
        this.activity = activity;
        this.ctx = ctx;
        this.root = root;
        this.display = display;
    }

    @SuppressWarnings("deprecation")
    long area() {
        if (display == null) return 0;
        DisplayMetrics m = new DisplayMetrics();
        display.getRealMetrics(m);
        return (long) m.widthPixels * m.heightPixels;
    }
}
