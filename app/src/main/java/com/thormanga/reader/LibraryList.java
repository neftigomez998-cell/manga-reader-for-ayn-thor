package com.thormanga.reader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** The browsable list of folders and books (shown on the bottom screen). */
final class LibraryList extends LinearLayout implements Session.Listener {
    private final Session s;
    private final TextView pathView, statusView;
    private final ListView list;
    private final LinearLayout browse, empty;
    private final TextView bSwap;
    private final Adapter adapter = new Adapter();
    private int shownSel = -1;

    LibraryList(Context c) {
        super(c);
        s = Session.get(c);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        int p = Ui.dp(c, 10);
        setPadding(p, p, p, p);

        // Browsing view
        browse = new LinearLayout(c);
        browse.setOrientation(VERTICAL);
        LinearLayout head = Ui.row(c);
        pathView = Ui.label(c, 16, Ui.TEXT, true);
        pathView.setSingleLine();
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        head.addView(pathView, Ui.weighted(c, 1));
        head.addView(Ui.button(c, "Up", v -> s.back()), Ui.wrap(c));
        head.addView(Ui.button(c, "Folder…", v -> s.pickFolder(s.activityFor(getContext()))), Ui.wrap(c));
        bSwap = Ui.button(c, "Swap", v -> s.toggleSwap());
        head.addView(bSwap, Ui.wrap(c));
        browse.addView(head, Ui.fullWidth(c));
        statusView = Ui.label(c, 13, Ui.DIM, false);
        browse.addView(statusView, Ui.fullWidth(c));
        list = new ListView(c);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setItemsCanFocus(false);
        list.setFocusable(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            s.select(pos);
            s.openSelected();
        });
        browse.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(browse, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // First-run view
        empty = new LinearLayout(c);
        empty.setOrientation(VERTICAL);
        empty.setGravity(Gravity.CENTER);
        TextView t = Ui.label(c, 26, Ui.ACCENT, true);
        t.setText("Thor Manga");
        t.setGravity(Gravity.CENTER);
        empty.addView(t, Ui.fullWidth(c));
        TextView d = Ui.label(c, 15, Ui.TEXT, false);
        d.setText("Choose the folder where you keep your manga.\nCBZ / ZIP files and folders of images both work.");
        d.setGravity(Gravity.CENTER);
        empty.addView(d, Ui.fullWidth(c));
        TextView pick = Ui.button(c, "Choose manga folder", v -> s.pickFolder(s.activityFor(getContext())));
        pick.setSelected(true);
        empty.addView(pick, Ui.wrap(c));
        addView(empty, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
        boolean hasTree = s.tree != null;
        empty.setVisibility(hasTree ? GONE : VISIBLE);
        browse.setVisibility(hasTree ? VISIBLE : GONE);
        if (!hasTree) return;
        pathView.setText(s.pathText());
        bSwap.setVisibility(s.dual() ? VISIBLE : GONE);
        if (s.scanning) statusView.setText("Scanning…");
        else if (s.libError != null) statusView.setText(s.libError);
        else if (s.entries.isEmpty()) statusView.setText("Nothing here yet. Put .cbz files or folders of images in this folder.");
        else statusView.setText("↑↓ choose   A open   B back   Y change folder");
        adapter.notifyDataSetChanged();
        if (s.libSel != shownSel) {
            shownSel = s.libSel;
            int first = list.getFirstVisiblePosition(), last = list.getLastVisiblePosition();
            if (shownSel < first || shownSel > last || last < 0) list.setSelection(Math.max(0, shownSel - 2));
            else if (shownSel == last) list.smoothScrollToPosition(Math.min(s.entries.size() - 1, shownSel + 1));
            else if (shownSel == first) list.smoothScrollToPosition(Math.max(0, shownSel - 1));
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return s.entries.size();
        }

        @Override
        public Object getItem(int i) {
            return s.entries.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Context c = parent.getContext();
            LinearLayout row;
            TextView name, sub;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                name = (TextView) row.getChildAt(0);
                sub = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(c);
                row.setOrientation(VERTICAL);
                int p = Ui.dp(c, 12);
                row.setPadding(p, Ui.dp(c, 9), p, Ui.dp(c, 9));
                name = Ui.label(c, 17, Ui.TEXT, true);
                name.setSingleLine();
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                sub = Ui.label(c, 13, Ui.DIM, false);
                row.addView(name);
                row.addView(sub);
            }
            Session.Entry e = s.entries.get(pos);
            String icon = e.type == Session.Entry.FOLDER ? "📁  " : e.type == Session.Entry.ARCHIVE ? "📖  " : "▶  ";
            name.setText(icon + (e.type == Session.Entry.ARCHIVE ? Book.stripExt(e.name) : e.name));
            String prog = s.progress(e);
            String detail;
            if (e.type == Session.Entry.FOLDER) detail = "Folder";
            else if (e.type == Session.Entry.HERE) detail = e.images + " images" + (prog != null ? "  ·  " + prog : "");
            else detail = prog != null ? prog : "Not started";
            sub.setText(detail);
            row.setBackground(pos == s.libSel ? Ui.round(c, Ui.SELECTED_ROW) : null);
            return row;
        }
    }
}
