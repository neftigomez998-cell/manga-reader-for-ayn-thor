package com.thormanga.reader;

import android.content.Context;
import android.net.Uri;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.exception.UnsupportedRarV5Exception;
import com.github.junrar.rarfile.FileHeader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * A .cbr / .rar archive. RAR files can't be read page-by-page reliably (solid archives),
 * so the images are unpacked once into the app cache and read from there.
 * Supports RAR 4 and older (what most CBR files use); RAR5 is reported with a clear message.
 */
final class RarBook extends Book {
    private static final String INDEX = "pages.txt";
    private final ArrayList<File> pages;

    private RarBook(String id, String title, ArrayList<File> pages) {
        super(id, title);
        this.pages = pages;
    }

    static RarBook open(Context c, Uri uri, String title) throws IOException {
        long size = ZipBook.querySize(c, uri);
        String key = Integer.toHexString(uri.toString().hashCode()) + "_" + size;
        File root = new File(c.getCacheDir(), "rar");
        File dir = new File(root, key);
        File index = new File(dir, INDEX);
        if (size <= 0 || !index.exists()) extract(c, uri, dir);
        index.setLastModified(System.currentTimeMillis());
        dir.setLastModified(System.currentTimeMillis());
        prune(root, 3);

        ArrayList<File> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(index), Charset.forName("UTF-8")))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) list.add(new File(dir, line));
            }
        }
        return new RarBook(uri.toString(), title, list);
    }

    private static void extract(Context c, Uri uri, File dir) throws IOException {
        deleteTree(dir);
        if (!dir.mkdirs()) throw new IOException("Can't create cache folder");
        File src = new File(dir, "source.cbr");
        ZipBook.copy(c, uri, src);

        // {original name, extracted file name}
        ArrayList<String[]> found = new ArrayList<>();
        try (Archive a = new Archive(src)) {
            if (a.isEncrypted()) throw new IOException("This CBR is password-protected");
            int i = 0;
            for (FileHeader fh : a.getFileHeaders()) {
                if (fh.isDirectory()) continue;
                String name = fh.getFileName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1);
                boolean keep = isImage(name) && !name.contains("__MACOSX") && !base.startsWith(".");
                if (keep) {
                    String out = String.format(Locale.ROOT, "%05d.%s", i++, ext(name));
                    try (OutputStream os = new FileOutputStream(new File(dir, out))) {
                        a.extractFile(fh, os);
                    }
                    found.add(new String[]{name, out});
                } else {
                    // Still decompress it: entries in a solid archive must be read in order.
                    a.extractFile(fh, NULL_OUT);
                }
            }
        } catch (UnsupportedRarV5Exception e) {
            deleteTree(dir);
            throw new IOException("this CBR uses the newer RAR5 format, which isn't supported yet. Convert it to CBZ.");
        } catch (RarException e) {
            deleteTree(dir);
            throw new IOException("couldn't read this CBR (" + e.getMessage() + ")");
        } finally {
            src.delete();
        }

        Collections.sort(found, (x, y) -> NATURAL.compare(x[0], y[0]));
        File tmp = new File(dir, INDEX + ".part");
        try (Writer w = new FileWriter(tmp)) {
            for (String[] f : found) w.write(f[1] + "\n");
        }
        if (!tmp.renameTo(new File(dir, INDEX))) throw new IOException("Can't write cache index");
    }

    private static final OutputStream NULL_OUT = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    private static void prune(File root, int keep) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= keep) return;
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < dirs.length; i++) deleteTree(dirs[i]);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    @Override
    int count() {
        return pages.size();
    }

    @Override
    InputStream open(int index) throws IOException {
        return new FileInputStream(pages.get(index));
    }
}
