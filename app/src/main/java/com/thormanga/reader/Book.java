package com.thormanga.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Locale;

/** A readable book: an ordered list of page images. */
abstract class Book {
    static final int MAX_DIM = 8192;
    static final long MAX_PIXELS = 16_000_000L;

    final String id;
    final String title;

    Book(String id, String title) {
        this.id = id;
        this.title = title;
    }

    abstract int count();

    abstract InputStream open(int index) throws IOException;

    void close() {
    }

    /** Returns {width, height} of a page without decoding it, or null. */
    int[] bounds(int index) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = new BufferedInputStream(open(index), 16384)) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable t) {
            return null;
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        return new int[]{o.outWidth, o.outHeight};
    }

    /** Decodes a page so that it is about {@code targetWidth} pixels wide. */
    Bitmap decode(int index, int targetWidth) throws IOException {
        int[] wh = bounds(index);
        if (wh == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(wh[0], targetWidth);
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try (InputStream in = new BufferedInputStream(open(index), 65536)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        return b == null ? null : fit(b, targetWidth);
    }

    static int sampleFor(int size, int target) {
        int s = 1;
        if (target <= 0) return 1;
        while (size / (s * 2) >= target) s *= 2;
        return s;
    }

    /** Scales a decoded bitmap down to the target width and within GPU-safe limits. */
    static Bitmap fit(Bitmap b, int targetWidth) {
        int w = b.getWidth(), h = b.getHeight();
        float scale = 1f;
        if (targetWidth > 0 && w > targetWidth * 1.2f) scale = targetWidth / (float) w;
        if (h * scale > MAX_DIM) scale = MAX_DIM / (float) h;
        if (w * scale > MAX_DIM) scale = Math.min(scale, MAX_DIM / (float) w);
        double px = (double) w * scale * h * scale;
        if (px > MAX_PIXELS) scale *= (float) Math.sqrt(MAX_PIXELS / px);
        if (scale < 0.999f) {
            Bitmap s = Bitmap.createScaledBitmap(b,
                    Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    static Bitmap decodeBytes(byte[] data, int targetHeight) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outHeight <= 0) return null;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sampleFor(o.outHeight, targetHeight);
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        return b == null ? null : fit(b, 0);
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isImage(String name) {
        switch (ext(name)) {
            case "jpg": case "jpeg": case "png": case "webp": case "gif":
            case "bmp": case "heic": case "heif": case "avif":
                return true;
            default:
                return false;
        }
    }

    static boolean isArchive(String name) {
        String e = ext(name);
        return e.equals("cbz") || e.equals("zip");
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "Page 2" sorts before "Page 10". */
    static final Comparator<String> NATURAL = (a, b) -> {
        int i = 0, j = 0, na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (si < na && a.charAt(si) == '0') si++;
                while (sj < nb && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < na && Character.isDigit(a.charAt(ei))) ei++;
                while (ej < nb && Character.isDigit(b.charAt(ej))) ej++;
                int la = ei - si, lb = ej - sj;
                if (la != lb) return la - lb;
                for (int k = 0; k < la; k++) {
                    int d = a.charAt(si + k) - b.charAt(sj + k);
                    if (d != 0) return d;
                }
                i = ei;
                j = ej;
            } else {
                int d = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (d != 0) return d;
                i++;
                j++;
            }
        }
        return (na - i) - (nb - j);
    };
}
