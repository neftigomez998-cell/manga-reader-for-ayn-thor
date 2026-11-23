package com.thormanga.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Big cover + details for the highlighted library item (shown on the top screen). */
final class LibraryPreview extends LinearLayout implements Session.Listener {
    private final Session s;
    private final ImageView cover;
    private final TextView name, detail;
    private Bitmap shown;

    LibraryPreview(Context c) {
        super(c);
        s = Session.get(c);
        setOrientation(HORIZONTAL);
        setBackgroundColor(Ui.BG);
        int p = Ui.dp(c, 24);
        setPadding(p, p, p, p);
        setGravity(Gravity.CENTER_VERTICAL);

        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(cover, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setPadding(p, 0, 0, 0);
        TextView brand = Ui.label(c, 14, Ui.ACCENT, true);
        brand.setText("THOR MANGA");
        brand.setLetterSpacing(0.2f);
        col.addView(brand);
        name = Ui.label(c, 28, Ui.TEXT, true);
        name.setMaxLines(3);
        col.addView(name, Ui.fullWidth(c));
        detail = Ui.label(c, 17, Ui.DIM, false);
        col.addView(detail, Ui.fullWidth(c));
        TextView hint = Ui.label(c, 14, Ui.DIM, false);
        hint.setText("\nD-pad ↑↓  choose\nA  open      B  back\nX  swap screens\nY  change folder");
        col.addView(hint, Ui.fullWidth(c));
        addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        s.addListener(this);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        s.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSessionChanged() {
        update();
    }

    private void update() {
        Bitmap b = null;
        if (s.tree == null) {
            name.setText("Welcome");
            detail.setText("Pick your manga folder on the other screen to get started.");
        } else {
            Session.Entry e = s.selected();
            if (e == null) {
                name.setText(s.crumb() != null ? s.crumb().name : "Library");
                detail.setText(s.scanning ? "Scanning…" : "This folder is empty.");
            } else {
                name.setText(s.entryTitle(e));
                String prog = s.progress(e);
                if (e.type == Session.Entry.FOLDER) detail.setText("Folder — press A to open");
                else if (e.type == Session.Entry.HERE) detail.setText(e.images + " pages" + (prog != null ? "\n" + prog : "\nNot started"));
                else detail.setText(prog != null ? prog : "Not started");
                b = s.cover(e);
            }
        }
        if (b != shown) {
            shown = b;
            cover.setImageBitmap(b);
        }
    }
}
