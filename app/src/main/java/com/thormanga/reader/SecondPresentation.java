package com.thormanga.reader;

import android.app.Activity;
import android.app.Presentation;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Second-screen window used when the other display is a presentation display. */
final class SecondPresentation extends Presentation {
    private final Activity owner;
    private final Session s;
    private ScreenHost host;

    SecondPresentation(Activity owner, Display display) {
        super(owner, display);
        this.owner = owner;
        this.s = Session.get(owner);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        if (getWindow() != null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        host = new ScreenHost(owner, getContext(), root, getDisplay());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Ui.immersive(getWindow());
        s.addHost(host);
    }

    @Override
    protected void onStop() {
        s.removeHost(host);
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return s.handleKeyEvent(e, owner) || super.dispatchKeyEvent(e);
    }
}
