package com.thormanga.reader;

import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;

/**
 * Launches on the main (top) screen, then puts a second window on the other screen:
 * a Presentation if the display allows it, otherwise a second activity launched onto that display.
 */
public class MainActivity extends HostActivity {
    static final String EXTRA_MAIN_DISPLAY = "mainDisplay";
    private SecondPresentation presentation;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s.addHost(host);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction())) {
            Uri u = i.getData();
            if (u != null) s.openUri(u);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ensureSecondScreen();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) tearDownSecondScreen();
    }

    @Override
    protected void onDestroy() {
        tearDownSecondScreen();
        s.removeHost(host);
        super.onDestroy();
    }

    private void ensureSecondScreen() {
        if (presentation != null || s.secondActivity != null || s.secondLaunching || s.secondFailed) return;
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display mine = Ui.displayOf(this);
        Display other = null;
        for (Display d : dm.getDisplays()) {
            if (mine != null && d.getDisplayId() == mine.getDisplayId()) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            other = d;
            break;
        }
        if (other == null) return;

        if ((other.getFlags() & Display.FLAG_PRESENTATION) != 0) {
            try {
                presentation = new SecondPresentation(this, other);
                presentation.show();
                return;
            } catch (RuntimeException e) { // includes WindowManager.InvalidDisplayException
                presentation = null;
            }
        }

        try {
            Intent i = new Intent(this, SecondScreenActivity.class);
            i.putExtra(EXTRA_MAIN_DISPLAY, mine != null ? mine.getDisplayId() : Display.DEFAULT_DISPLAY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(other.getDisplayId());
            s.secondLaunching = true;
            startActivity(i, o.toBundle());
        } catch (Exception e) {
            s.secondLaunching = false;
            s.secondFailed = true;
        }
    }

    private void tearDownSecondScreen() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        if (s.secondActivity != null) {
            s.secondActivity.finish();
            s.secondActivity = null;
        }
    }
}
