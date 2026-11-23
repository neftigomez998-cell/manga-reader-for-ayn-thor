package com.thormanga.reader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * App-wide state shared by both screens: the library, the open book, reading position,
 * page images, and which screen shows what.
 */
final class Session {
    interface Listener {
        void onSessionChanged();
    }

    enum Mode { SPLIT, PAGE, STRIP }

    static final int REQ_TREE = 7001;
    static final float DEFAULT_ASPECT = 1.42f;

    private static Session sInstance;

    static Session get(Context c) {
        if (sInstance == null) sInstance = new Session(c.getApplicationContext());
        return sInstance;
    }

    final Context app;
    final SharedPreferences prefs;
    final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService opener = Executors.newSingleThreadExecutor();
    private final ExecutorService boundsExec = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache;
    private final HashMap<Integer, String> lastKey = new HashMap<>();
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final HashSet<Integer> handledKeys = new HashSet<>();
    final List<ScreenHost> hosts = new ArrayList<>();

    // Second screen bookkeeping
    Activity secondActivity;
    boolean secondFailed;
    boolean secondLaunching;

    // Reader state
    Book book;
    volatile int bookGen;
    volatile int page;
    Mode mode;
    boolean rtl, swap, fitWidth, volumeKeys, overlay;
    boolean enterAtEnd;
    float[] aspects = new float[0];
    private int anchor;
    private float anchorFrac;
    private float[] stripY;
    private float stripColW = -1;
    PageView mainView, subView;

    // Library state
    static final class Entry {
        static final int FOLDER = 0, ARCHIVE = 1, HERE = 2;
        final int type;
        final String name, docId;
        final Uri uri;
        int images;

        Entry(int type, String name, String docId, Uri uri) {
            this.type = type;
            this.name = name;
            this.docId = docId;
            this.uri = uri;
        }
    }

    static final class Crumb {
        final String docId, name;
        int sel;

        Crumb(String docId, String name) {
            this.docId = docId;
            this.name = name;
        }
    }

    Uri tree;
    final ArrayList<Crumb> path = new ArrayList<>();
    final ArrayList<Entry> entries = new ArrayList<>();
    int libSel;
    boolean scanning;
    String libError;
    private int scanGen;
    private final LruCache<String, Bitmap> covers = new LruCache<>(24);
    private final HashSet<String> coverPending = new HashSet<>();
    private final HashSet<String> coverFailed = new HashSet<>();

    private Session(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("thor_manga", Context.MODE_PRIVATE);
        Mode m;
        try {
            m = Mode.valueOf(prefs.getString("mode", Mode.SPLIT.name()));
        } catch (Exception e) {
            m = Mode.SPLIT;
        }
        mode = m;
        rtl = prefs.getBoolean("rtl", true);
        swap = prefs.getBoolean("swap", false);
        fitWidth = prefs.getBoolean("fitWidth", false);
        volumeKeys = prefs.getBoolean("volumeKeys", true);
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 3, 768L << 20);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getAllocationByteCount();
            }
        };
        String t = prefs.getString("tree", null);
        if (t != null) {
            Uri u = Uri.parse(t);
            if (hasPermission(u)) setTree(u);
        }
    }

    // ------------------------------------------------------------------ listeners

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onSessionChanged();
    }

    void geometryChanged() {
        stripY = null;
        main.post(this::notifyChanged);
    }

    // ------------------------------------------------------------------ screens

    void addHost(ScreenHost h) {
        if (!hosts.contains(h)) hosts.add(h);
        relayout();
    }

    void removeHost(ScreenHost h) {
        if (hosts.remove(h)) {
            h.root.removeAllViews();
            relayout();
        }
    }

    boolean dual() {
        return hosts.size() >= 2;
    }

    private ScreenHost bigHost() {
        ScreenHost best = null;
        for (ScreenHost h : hosts) if (best == null || h.area() > best.area()) best = h;
        return best;
    }

    Activity activityFor(Context c) {
        for (ScreenHost h : hosts) if (h.ctx == c) return h.activity;
        return hosts.isEmpty() ? null : hosts.get(0).activity;
    }

    /** Decides what each screen shows and rebuilds its views. */
    void relayout() {
        for (ScreenHost h : hosts) h.root.removeAllViews();
        if (hosts.isEmpty()) return;
        ScreenHost big = bigHost(), small = null;
        for (ScreenHost h : hosts) if (h != big) { small = h; break; }
        stripY = null;

        if (book == null) {
            if (small == null) {
                big.root.addView(new LibraryList(big.ctx), match());
            } else {
                ScreenHost listHost = swap ? big : small;
                ScreenHost previewHost = swap ? small : big;
                previewHost.root.addView(new LibraryPreview(previewHost.ctx), match());
                listHost.root.addView(new LibraryList(listHost.ctx), match());
            }
        } else if (small == null) {
            big.root.addView(new PageView(big.ctx, PageView.MAIN), match());
            big.root.addView(new ControlPanel(big.ctx, true, false), bottom());
        } else if (mode == Mode.PAGE) {
            ScreenHost pageHost = swap ? small : big;
            ScreenHost ctl = swap ? big : small;
            pageHost.root.addView(new PageView(pageHost.ctx, PageView.MAIN), match());
            ctl.root.addView(new ControlPanel(ctl.ctx, false, true), match());
        } else {
            // The page continues from the top screen onto the bottom screen.
            big.root.addView(new PageView(big.ctx, PageView.MAIN), match());
            small.root.addView(new PageView(small.ctx, PageView.SUB), match());
            small.root.addView(new ControlPanel(small.ctx, true, false), bottom());
        }
        notifyChanged();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams bottom() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    }

    void flash(String msg) {
        ScreenHost h = bigHost();
        Toast.makeText(h != null ? h.ctx : app, msg, Toast.LENGTH_SHORT).show();
    }

    void exitApp() {
        for (ScreenHost h : new ArrayList<>(hosts)) h.activity.finish();
        if (secondActivity != null) secondActivity.finish();
    }

    // ------------------------------------------------------------------ geometry (in inches)

    float regionH(PageView v) {
        return (v == null || v.getHeight() == 0) ? 0f : v.getHeight() / v.ppi;
    }

    private boolean useSub() {
        return mode != Mode.PAGE && subView != null && subView.getHeight() > 0;
    }

    float viewportH() {
        return regionH(mainView) + (useSub() ? regionH(subView) : 0f);
    }

    float colW() {
        float w = Float.MAX_VALUE;
        if (mainView != null && mainView.getWidth() > 0) w = Math.min(w, mainView.getWidth() / mainView.ppi);
        if (useSub()) w = Math.min(w, subView.getWidth() / subView.ppi);
        return w == Float.MAX_VALUE ? 0f : w;
    }

    float maxPpi() {
        float p = 0;
        if (mainView != null) p = mainView.ppi;
        if (useSub()) p = Math.max(p, subView.ppi);
        return p > 0 ? p : 300f;
    }

    float aspect(int i) {
        if (i < 0 || i >= aspects.length) return DEFAULT_ASPECT;
        float a = aspects[i];
        return a > 0 ? a : DEFAULT_ASPECT;
    }

    private void setAspect(int i, float a) {
        if (i >= 0 && i < aspects.length && aspects[i] != a) {
            aspects[i] = a;
            stripY = null;
        }
    }

    /** Split mode: the current page placed across both screens. Returns {width, top, height}. */
    float[] splitPlace() {
        float a = aspect(page);
        float total = viewportH();
        float w = colW();
        float h = w * a;
        if (h > total) {
            h = total;
            w = h / a;
        }
        return new float[]{w, (total - h) / 2f, h};
    }

    float[] stripYs() {
        float cw = colW();
        if (stripY == null || cw != stripColW || stripY.length != aspects.length + 1) {
            int n = aspects.length;
            float[] y = new float[n + 1];
            for (int i = 0; i < n; i++) y[i + 1] = y[i] + cw * aspect(i);
            stripY = y;
            stripColW = cw;
        }
        return stripY;
    }

    int stripIndexAt(float y) {
        float[] ys = stripYs();
        int lo = 0, hi = ys.length - 2;
        if (hi < 0) return 0;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (ys[mid] <= y) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    float stripOffset() {
        float[] ys = stripYs();
        if (ys.length < 2) return 0;
        int a = Math.max(0, Math.min(anchor, ys.length - 2));
        return ys[a] + anchorFrac * (ys[a + 1] - ys[a]);
    }

    float stripMax() {
        float[] ys = stripYs();
        return Math.max(0f, ys[ys.length - 1] - viewportH());
    }

    void setStripOffset(float off) {
        float[] ys = stripYs();
        if (ys.length < 2) return;
        off = Math.max(0f, Math.min(off, stripMax()));
        int i = stripIndexAt(off);
        float h = ys[i + 1] - ys[i];
        anchor = i;
        anchorFrac = h > 0 ? (off - ys[i]) / h : 0;
        if (page != i) {
            page = i;
            savePos();
        }
        notifyChanged();
    }

    void scrollStrip(float delta) {
        setStripOffset(stripOffset() + delta);
    }

    // ------------------------------------------------------------------ navigation

    int count() {
        return book == null ? 0 : book.count();
    }

    void goTo(int p) {
        if (book == null) return;
        p = Math.max(0, Math.min(p, count() - 1));
        if (p == page && mode != Mode.STRIP) return;
        page = p;
        anchor = p;
        anchorFrac = 0;
        savePos();
        notifyChanged();
    }

    void next() {
        if (book == null) return;
        if (mode == Mode.STRIP) {
            if (stripOffset() >= stripMax() - 0.01f) flash("End of chapter");
            else scrollStrip(viewportH() * 0.85f);
            return;
        }
        if (page >= count() - 1) {
            flash("Last page");
            return;
        }
        enterAtEnd = false;
        goTo(page + 1);
    }

    void prev() {
        if (book == null) return;
        if (mode == Mode.STRIP) {
            scrollStrip(-viewportH() * 0.85f);
            return;
        }
        if (page <= 0) {
            flash("First page");
            return;
        }
        enterAtEnd = false;
        goTo(page - 1);
    }

    /** Left tap / left button: next page for manga (right-to-left), previous for comics. */
    void left() {
        if (rtl) next(); else prev();
    }

    void right() {
        if (rtl) prev(); else next();
    }

    void setMode(Mode m) {
        if (m == mode) return;
        if (m == Mode.STRIP) {
            anchor = page;
            anchorFrac = 0;
        }
        mode = m;
        overlay = false;
        prefs.edit().putString("mode", m.name()).apply();
        relayout();
    }

    void cycleMode() {
        Mode[] all = Mode.values();
        setMode(all[(mode.ordinal() + 1) % all.length]);
        flash(modeName(mode));
    }

    static String modeName(Mode m) {
        switch (m) {
            case SPLIT: return "Split: one page across both screens";
            case PAGE: return "Page: top screen page, bottom screen controls";
            default: return "Strip: continuous scroll (webtoons)";
        }
    }

    void toggleOverlay() {
        overlay = !overlay;
        notifyChanged();
    }

    void toggleRtl() {
        rtl = !rtl;
        prefs.edit().putBoolean("rtl", rtl).apply();
        notifyChanged();
    }

    void toggleFit() {
        fitWidth = !fitWidth;
        prefs.edit().putBoolean("fitWidth", fitWidth).apply();
        notifyChanged();
    }

    void toggleSwap() {
        if (!dual()) return;
        swap = !swap;
        prefs.edit().putBoolean("swap", swap).apply();
        relayout();
    }

    private void savePos() {
        if (book != null) {
            prefs.edit().putInt("pos:" + book.id, page).putInt("cnt:" + book.id, book.count()).apply();
        }
    }

    // ------------------------------------------------------------------ keys / gamepad

    boolean handleKeyEvent(KeyEvent e, Activity from) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            boolean h = onKey(code, e.getRepeatCount(), from);
            if (h) handledKeys.add(code);
            return h;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) return handledKeys.remove(code);
        return false;
    }

    private boolean onKey(int code, int repeat, Activity from) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
            if (repeat == 0 && !back()) exitApp();
            return true;
        }
        if (book == null) return libraryKey(code, from);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                right();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                left();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                next();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_PAGE_UP:
                prev();
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (!volumeKeys) return false;
                next();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (!volumeKeys) return false;
                prev();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mode == Mode.STRIP) scrollStrip(viewportH() * 0.5f);
                else if (mode == Mode.PAGE && mainView != null && mainView.scrollStep(1)) { /* panned */ }
                else next();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mode == Mode.STRIP) scrollStrip(-viewportH() * 0.5f);
                else if (mode == Mode.PAGE && mainView != null && mainView.scrollStep(-1)) { /* panned */ }
                else if (page > 0) {
                    enterAtEnd = mode == Mode.PAGE;
                    goTo(page - 1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                cycleMode();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                toggleOverlay();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                toggleSwap();
                return true;
            default:
                return false;
        }
    }

    private boolean libraryKey(int code, Activity from) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN: moveSel(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: moveSel(-1); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: moveSel(8); return true;
            case KeyEvent.KEYCODE_BUTTON_L1: moveSel(-8); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                openSelected();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                toggleSwap();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                pickFolder(from);
                return true;
            default:
                return false;
        }
    }

    /** Back: hide controls, close the book, or go up a folder. Returns false at the top. */
    boolean back() {
        if (book != null) {
            if (overlay) {
                overlay = false;
                notifyChanged();
            } else {
                closeBook();
            }
            return true;
        }
        if (path.size() > 1) {
            path.remove(path.size() - 1);
            libSel = path.get(path.size() - 1).sel;
            scan();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ library

    private boolean hasPermission(Uri u) {
        for (UriPermission p : app.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) return true;
        }
        return false;
    }

    void pickFolder(Activity a) {
        if (a == null) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            a.startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            flash("No file picker available");
        }
    }

    void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_TREE || res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            app.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        prefs.edit().putString("tree", u.toString()).apply();
        setTree(u);
        notifyChanged();
    }

    private void setTree(Uri u) {
        tree = u;
        path.clear();
        String rootId = DocumentsContract.getTreeDocumentId(u);
        String name = queryName(DocumentsContract.buildDocumentUriUsingTree(u, rootId));
        path.add(new Crumb(rootId, name != null ? name : "Library"));
        libSel = 0;
        scan();
    }

    private String queryName(Uri doc) {
        try (Cursor c = app.getContentResolver().query(doc, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    Crumb crumb() {
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    String pathText() {
        StringBuilder b = new StringBuilder();
        for (Crumb c : path) {
            if (b.length() > 0) b.append("  ›  ");
            b.append(c.name);
        }
        return b.toString();
    }

    void scan() {
        if (tree == null || path.isEmpty()) return;
        final Uri t = tree;
        final String docId = crumb().docId;
        final int gen = ++scanGen;
        scanning = true;
        notifyChanged();
        opener.execute(() -> {
            ArrayList<Entry> folders = new ArrayList<>(), archives = new ArrayList<>();
            int images = 0;
            String err = null;
            try (Cursor c = app.getContentResolver().query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(t, docId),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (name == null || name.startsWith(".")) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        folders.add(new Entry(Entry.FOLDER, name, id, null));
                    } else if (Book.isArchive(name)) {
                        archives.add(new Entry(Entry.ARCHIVE, name, id, DocumentsContract.buildDocumentUriUsingTree(t, id)));
                    } else if (Book.isImage(name)) {
                        images++;
                    }
                }
            } catch (Exception e) {
                err = "Couldn't read this folder: " + e.getMessage();
            }
            Collections.sort(folders, (a, b) -> Book.NATURAL.compare(a.name, b.name));
            Collections.sort(archives, (a, b) -> Book.NATURAL.compare(a.name, b.name));
            final ArrayList<Entry> all = new ArrayList<>();
            if (images > 0) {
                Entry here = new Entry(Entry.HERE, "Read this folder", docId, null);
                here.images = images;
                all.add(here);
            }
            all.addAll(folders);
            all.addAll(archives);
            final String ferr = err;
            main.post(() -> {
                if (gen != scanGen) return;
                entries.clear();
                entries.addAll(all);
                scanning = false;
                libError = ferr;
                libSel = Math.max(0, Math.min(libSel, entries.size() - 1));
                notifyChanged();
            });
        });
    }

    Entry selected() {
        return libSel >= 0 && libSel < entries.size() ? entries.get(libSel) : null;
    }

    void moveSel(int d) {
        if (entries.isEmpty()) return;
        libSel = Math.max(0, Math.min(entries.size() - 1, libSel + d));
        notifyChanged();
    }

    void select(int i) {
        libSel = i;
        notifyChanged();
    }

    void openSelected() {
        Entry e = selected();
        if (e == null) return;
        if (e.type == Entry.FOLDER) {
            crumb().sel = libSel;
            path.add(new Crumb(e.docId, e.name));
            libSel = 0;
            scan();
        } else {
            openEntry(e);
        }
    }

    String entryTitle(Entry e) {
        if (e.type == Entry.HERE) return crumb() != null ? crumb().name : e.name;
        if (e.type == Entry.ARCHIVE) return Book.stripExt(e.name);
        return e.name;
    }

    String bookId(Entry e) {
        if (e.type == Entry.ARCHIVE) return e.uri.toString();
        if (e.type == Entry.HERE) return FolderBook.bookId(tree, e.docId);
        return null;
    }

    /** "Page 12 of 180", or null if never opened. */
    String progress(Entry e) {
        String id = bookId(e);
        if (id == null || !prefs.contains("pos:" + id)) return null;
        int p = prefs.getInt("pos:" + id, 0) + 1, n = prefs.getInt("cnt:" + id, 0);
        if (n > 0 && p >= n) return "Finished";
        return n > 0 ? "Page " + p + " of " + n : "Page " + p;
    }

    void openEntry(final Entry e) {
        final Uri t = tree;
        final String title = entryTitle(e);
        flash("Opening " + title + "…");
        opener.execute(() -> {
            Book b = null;
            String err = null;
            try {
                b = e.type == Entry.ARCHIVE ? ZipBook.open(app, e.uri, title) : FolderBook.open(app, t, e.docId, title);
            } catch (Throwable x) {
                err = x.getMessage() != null ? x.getMessage() : x.toString();
            }
            deliverBook(b, err, title);
        });
    }

    /** Opens a .cbz/.zip handed to the app by a file manager. */
    void openUri(final Uri uri) {
        String n = queryName(uri);
        final String title = n != null ? Book.stripExt(n) : "Book";
        flash("Opening " + title + "…");
        opener.execute(() -> {
            Book b = null;
            String err = null;
            try {
                b = ZipBook.open(app, uri, title);
            } catch (Throwable x) {
                err = x.getMessage() != null ? x.getMessage() : x.toString();
            }
            deliverBook(b, err, title);
        });
    }

    private void deliverBook(final Book b, final String err, final String title) {
        main.post(() -> {
            if (b == null) {
                flash("Couldn't open " + title + ": " + err);
            } else if (b.count() == 0) {
                b.close();
                flash("No pages found in " + title);
            } else {
                setBook(b);
            }
        });
    }

    private void setBook(Book b) {
        if (book != null) closeBookQuiet();
        book = b;
        bookGen++;
        clearPages();
        aspects = new float[b.count()];
        page = Math.max(0, Math.min(prefs.getInt("pos:" + b.id, 0), b.count() - 1));
        anchor = page;
        anchorFrac = 0;
        overlay = false;
        enterAtEnd = false;
        loadBounds(b, bookGen, page);
        relayout();
        if (!dual() || mode != Mode.PAGE) flash("Tap the middle of the page for controls");
    }

    void closeBook() {
        closeBookQuiet();
        relayout();
    }

    private void closeBookQuiet() {
        if (book == null) return;
        savePos();
        Book b = book;
        book = null;
        bookGen++;
        clearPages();
        aspects = new float[0];
        b.close();
    }

    private void clearPages() {
        cache.evictAll();
        lastKey.clear();
        pending.clear();
        failed.clear();
        stripY = null;
    }

    private void loadBounds(final Book b, final int gen, final int start) {
        boundsExec.execute(() -> {
            int n = b.count();
            int[] idx = new int[16];
            float[] val = new float[16];
            int k = 0;
            for (int s = 0; s < n; s++) {
                if (gen != bookGen) return;
                int i = (start + s) % n;
                int[] wh = b.bounds(i);
                if (wh != null) {
                    idx[k] = i;
                    val[k] = wh[1] / (float) wh[0];
                    k++;
                }
                if (k == idx.length || (s == n - 1 && k > 0)) {
                    final int[] fi = idx.clone();
                    final float[] fv = val.clone();
                    final int fk = k;
                    main.post(() -> {
                        if (gen != bookGen) return;
                        for (int j = 0; j < fk; j++) setAspect(fi[j], fv[j]);
                        notifyChanged();
                    });
                    k = 0;
                }
            }
        });
    }

    // ------------------------------------------------------------------ page images

    static int bucket(int w) {
        if (w <= 64) return 64;
        if (w <= 1024) return ((w + 63) / 64) * 64;
        return Math.min(4096, ((w + 255) / 256) * 256);
    }

    private boolean wanted(int p) {
        return book != null && p >= page - 4 && p <= page + 14;
    }

    /** Returns the page image at roughly this width, or a lower-quality stand-in while it loads. */
    Bitmap bitmap(final int p, int targetWidth) {
        if (book == null || p < 0 || p >= book.count()) return null;
        final int tw = bucket(targetWidth);
        final String key = bookGen + ":" + p + ":" + tw;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        if (failed.contains(key)) return null;
        if (!pending.contains(key)) {
            pending.add(key);
            final int gen = bookGen;
            final Book bk = book;
            io.execute(() -> {
                Bitmap bm = null;
                if (gen == bookGen && wanted(p)) {
                    try {
                        bm = bk.decode(p, tw);
                    } catch (OutOfMemoryError oom) {
                        main.post(cache::evictAll);
                    } catch (Throwable ignored) {
                    }
                }
                final Bitmap fb = bm;
                final boolean skipped = gen == bookGen && !wanted(p) && bm == null;
                main.post(() -> {
                    pending.remove(key);
                    if (gen != bookGen) return;
                    if (fb != null) {
                        cache.put(key, fb);
                        lastKey.put(p, key);
                        if (p < aspects.length && aspects[p] <= 0) setAspect(p, fb.getHeight() / (float) fb.getWidth());
                        notifyChanged();
                    } else if (!skipped) {
                        failed.add(key);
                        notifyChanged();
                    }
                });
            });
        }
        String lk = lastKey.get(p);
        return lk != null ? cache.get(lk) : null;
    }

    boolean failed(int p, int targetWidth) {
        return failed.contains(bookGen + ":" + p + ":" + bucket(targetWidth));
    }

    void prefetch(int p, int targetWidth) {
        if (book != null && p >= 0 && p < book.count()) bitmap(p, targetWidth);
    }

    // ------------------------------------------------------------------ covers

    Bitmap cover(final Entry e) {
        if (e == null || tree == null) return null;
        final String key = e.type + ":" + (e.uri != null ? e.uri.toString() : e.docId);
        Bitmap b = covers.get(key);
        if (b != null || coverPending.contains(key) || coverFailed.contains(key)) return b;
        coverPending.add(key);
        final Uri t = tree;
        io.execute(() -> {
            Bitmap bm = null;
            try {
                bm = loadCover(t, e);
            } catch (Throwable ignored) {
            }
            final Bitmap fb = bm;
            main.post(() -> {
                coverPending.remove(key);
                if (fb != null) covers.put(key, fb); else coverFailed.add(key);
                notifyChanged();
            });
        });
        return null;
    }

    private Bitmap loadCover(Uri t, Entry e) throws Exception {
        ContentResolver cr = app.getContentResolver();
        if (e.type == Entry.ARCHIVE) {
            InputStream raw = cr.openInputStream(e.uri);
            if (raw == null) return null;
            try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(raw, 65536), Charset.forName("Cp437"))) {
                ZipEntry ze;
                while ((ze = z.getNextEntry()) != null) {
                    String n = ze.getName();
                    if (!ze.isDirectory() && Book.isImage(n) && !n.contains("__MACOSX")) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = z.read(buf)) > 0 && out.size() < 40_000_000) out.write(buf, 0, r);
                        return Book.decodeBytes(out.toByteArray(), 900);
                    }
                }
            }
            return null;
        }
        // Folder: first image by name.
        String best = null, bestName = null;
        try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(t, e.docId),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1);
                if (name != null && !name.startsWith(".") && Book.isImage(name)
                        && (bestName == null || Book.NATURAL.compare(name, bestName) < 0)) {
                    best = id;
                    bestName = name;
                }
            }
        }
        if (best == null) return null;
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(t, best);
        try (InputStream in = cr.openInputStream(doc)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return Book.decodeBytes(out.toByteArray(), 900);
        }
    }
}
