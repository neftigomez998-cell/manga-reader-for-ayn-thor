package com.thormanga.reader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A .cbz / .zip archive. The archive is copied to the app cache so pages can be read in any order. */
final class ZipBook extends Book {
    private final ZipFile zip;
    private final List<ZipEntry> pages;

    private ZipBook(String id, String title, ZipFile zip, List<ZipEntry> pages) {
        super(id, title);
        this.zip = zip;
        this.pages = pages;
    }

    static ZipBook open(Context c, Uri uri, String title) throws IOException {
        File dir = new File(c.getCacheDir(), "books");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Can't create cache folder");
        long size = querySize(c, uri);
        File f = new File(dir, Integer.toHexString(uri.toString().hashCode()) + "_" + size + ".cbz");
        if (size <= 0 || !f.exists() || f.length() != size) {
            File tmp = new File(dir, f.getName() + ".part");
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Can't read file");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Can't cache file");
        }
        f.setLastModified(System.currentTimeMillis());
        prune(dir, 4);

        ZipFile z = null;
        List<ZipEntry> list;
        try {
            z = new ZipFile(f);
            list = collect(z);
        } catch (Exception e) {
            if (z != null) try { z.close(); } catch (IOException ignored) { }
            // Archives made on Japanese / older systems may not use UTF-8 file names.
            z = new ZipFile(f, Charset.forName("Cp437"));
            list = collect(z);
        }
        return new ZipBook(uri.toString(), title, z, list);
    }

    private static List<ZipEntry> collect(ZipFile z) {
        ArrayList<ZipEntry> list = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n.contains("__MACOSX")) continue;
            String base = n.substring(n.lastIndexOf('/') + 1);
            if (base.startsWith(".")) continue;
            if (isImage(n)) list.add(e);
        }
        Collections.sort(list, (a, b) -> NATURAL.compare(a.getName(), b.getName()));
        return list;
    }

    private static long querySize(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void prune(File dir, int keep) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".cbz"));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < files.length; i++) files[i].delete();
    }

    @Override
    int count() {
        return pages.size();
    }

    @Override
    InputStream open(int index) throws IOException {
        return zip.getInputStream(pages.get(index));
    }

    @Override
    void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
        }
    }
}
