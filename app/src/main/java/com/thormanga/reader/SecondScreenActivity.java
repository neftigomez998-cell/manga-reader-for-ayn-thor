package com.thormanga.reader;

import android.os.Bundle;
import android.view.Display;

/** The window on the second (bottom) screen. */
public class SecondScreenActivity extends HostActivity {
    private boolean valid;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s.secondLaunching = false;
        int mainId = getIntent().getIntExtra(MainActivity.EXTRA_MAIN_DISPLAY, -1);
        Display d = Ui.displayOf(this);
        if (d == null || d.getDisplayId() == mainId) {
            // The system put us on the same screen as the main window: give up on dual-screen.
            s.secondFailed = true;
            finish();
            return;
        }
        valid = true;
        if (s.secondActivity != null && s.secondActivity != this) s.secondActivity.finish();
        s.secondActivity = this;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (valid) s.addHost(host);
    }

    @Override
    protected void onStop() {
        if (valid) s.removeHost(host);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (s.secondActivity == this) s.secondActivity = null;
        super.onDestroy();
    }
}
