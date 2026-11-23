package com.thormanga.reader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;

/** A folder of image files picked through Android's storage access framework. */
final class FolderBook extends Book {
    private final ContentResolver cr;
    private final ArrayList<Uri> pages;

    private FolderBook(String id, String title, ContentResolver cr, ArrayList<Uri> pages) {
        super(id, title);
        this.cr = cr;
        this.pages = pages;
    }

    static FolderBook open(Context c, Uri tree, String docId, String title) {
        ContentResolver cr = c.getContentResolver();
        ArrayList<String[]> found = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1);
                if (name != null && !name.startsWith(".") && isImage(name)) found.add(new String[]{id, name});
            }
        }
        Collections.sort(found, (a, b) -> NATURAL.compare(a[1], b[1]));
        ArrayList<Uri> uris = new ArrayList<>();
        for (String[] f : found) uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, f[0]));
        return new FolderBook(bookId(tree, docId), title, cr, uris);
    }

    static String bookId(Uri tree, String docId) {
        return tree + "#" + docId;
    }

    @Override
    int count() {
        return pages.size();
    }

    @Override
    InputStream open(int index) throws IOException {
        InputStream in = cr.openInputStream(pages.get(index));
        if (in == null) throw new IOException("Can't read page");
        return in;
    }
}
