package com.thormanga.reader;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Reading controls. Fills the bottom screen in Page mode; slides over the page in the other modes. */
final class ControlPanel extends LinearLayout implements Session.Listener {
    private final Session s;
    private final boolean overlayStyle;
    private final TextView title, info, bLeft, bRight, mSplit, mPage, mStrip, bDir, bFit, bSwap;
    private final SeekBar seek;
    private boolean updating;

    ControlPanel(Context c, boolean overlayStyle, boolean showPreview) {
        super(c);
        this.overlayStyle = overlayStyle;
        s = Session.get(c);
        setOrientation(VERTICAL);
        int p = Ui.dp(c, 10);
        setPadding(p, p, p, p);
        if (overlayStyle) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.PANEL);
            float r = Ui.dp(c, 18);
            g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            setBackground(g);
        } else {
            setBackground(new ColorDrawable(Ui.BG));
        }
        setClickable(true); // keep taps from falling through to the page underneath

        LinearLayout head = Ui.row(c);
        title = Ui.label(c, 17, Ui.TEXT, true);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, Ui.weighted(c, 1));
        info = Ui.label(c, 15, Ui.DIM, false);
        head.addView(info, Ui.wrap(c));
        if (overlayStyle) head.addView(Ui.button(c, "✕", v -> s.toggleOverlay()), Ui.wrap(c));
        addView(head, Ui.fullWidth(c));

        if (showPreview) {
            TextView nextLbl = Ui.label(c, 12, Ui.DIM, false);
            nextLbl.setText("NEXT PAGE  ·  tap to turn");
            addView(nextLbl, Ui.fullWidth(c));
            PageView preview = new PageView(c, PageView.PREVIEW);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
            addView(preview, lp);
        }

        seek = new SeekBar(c);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !updating) s.goTo(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        addView(seek, Ui.fullWidth(c));

        LinearLayout nav = Ui.row(c);
        bLeft = Ui.button(c, "", v -> s.left());
        bRight = Ui.button(c, "", v -> s.right());
        nav.addView(bLeft, Ui.weighted(c, 1));
        nav.addView(bRight, Ui.weighted(c, 1));
        addView(nav, Ui.fullWidth(c));

        LinearLayout modes = Ui.row(c);
        mSplit = Ui.button(c, "Split", v -> s.setMode(Session.Mode.SPLIT));
        mPage = Ui.button(c, "Page", v -> s.setMode(Session.Mode.PAGE));
        mStrip = Ui.button(c, "Strip", v -> s.setMode(Session.Mode.STRIP));
        modes.addView(mSplit, Ui.weighted(c, 1));
        modes.addView(mPage, Ui.weighted(c, 1));
        modes.addView(mStrip, Ui.weighted(c, 1));
        addView(modes, Ui.fullWidth(c));

        LinearLayout more = Ui.row(c);
        bDir = Ui.button(c, "", v -> s.toggleRtl());
        bFit = Ui.button(c, "", v -> s.toggleFit());
        bSwap = Ui.button(c, "Swap", v -> s.toggleSwap());
        TextView bLib = Ui.button(c, "Library", v -> s.closeBook());
        more.addView(bDir, Ui.weighted(c, 1.3f));
        more.addView(bFit, Ui.weighted(c, 1));
        more.addView(bSwap, Ui.weighted(c, 0.8f));
        more.addView(bLib, Ui.weighted(c, 1));
        addView(more, Ui.fullWidth(c));
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
        if (s.book == null) return;
        if (overlayStyle) setVisibility(s.overlay ? VISIBLE : GONE);
        updating = true;
        title.setText(s.book.title);
        info.setText((s.page + 1) + " / " + s.count());
        seek.setMax(Math.max(0, s.count() - 1));
        seek.setProgress(s.page);
        seek.setLayoutDirection(s.rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        updating = false;
        bLeft.setText(s.rtl ? "‹  Next" : "‹  Prev");
        bRight.setText(s.rtl ? "Prev  ›" : "Next  ›");
        mSplit.setSelected(s.mode == Session.Mode.SPLIT);
        mPage.setSelected(s.mode == Session.Mode.PAGE);
        mStrip.setSelected(s.mode == Session.Mode.STRIP);
        bDir.setText(s.rtl ? "Manga ←" : "Comic →");
        bFit.setText(s.fitWidth ? "Fit width" : "Fit page");
        bFit.setVisibility(s.mode == Session.Mode.PAGE ? VISIBLE : GONE);
        bSwap.setVisibility(s.dual() && s.mode == Session.Mode.PAGE ? VISIBLE : GONE);
    }
}
