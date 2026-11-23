package com.thormanga.reader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Shared behaviour for the windows on both screens. */
abstract class HostActivity extends Activity {
    protected Session s;
    protected ScreenHost host;
    protected FrameLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = Session.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        host = new ScreenHost(this, this, root, Ui.displayOf(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(getWindow());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(getWindow());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return s.handleKeyEvent(e, this) || super.dispatchKeyEvent(e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        s.onActivityResult(req, res, data);
    }
}
