package dev.hfps.probe.writer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Inserts one file per format into MediaStore.Images the way Hoard's copy would (IS_PENDING while writing). */
public class WriterActivity extends Activity {
    static final String TAG = "ProbeWriter";
    static final String[][] FORMATS = {
        {"ARW", "image/x-sony-arw"}, {"NEF", "image/x-nikon-nef"}, {"CR3", "image/x-canon-cr3"},
        {"RAF", "image/x-fuji-raf"}, {"DNG", "image/x-adobe-dng"}, {"ORF", "image/x-olympus-orf"},
        {"RW2", "image/x-panasonic-rw2"}, {"JPG", "image/jpeg"},
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String dir = getIntent().getStringExtra("dir");
        if (dir == null) dir = "Pictures/Hoard/mediastore-probe/";
        final String relativePath = dir;
        final boolean cr3 = getIntent().getBooleanExtra("cr3variants", false);
        new Thread(() -> {
            if (cr3) { cr3Variants(relativePath); Log.i(TAG, "DONE"); finish(); return; }
            for (String[] f : FORMATS) {
                try { insert(relativePath, f[0], f[1]); }
                catch (Throwable t) { Log.i(TAG, "RESULT " + f[0] + " insert=FAILED " + t); }
            }
            Log.i(TAG, "DONE");
            finish();
        }).start();
    }

    /** CR3 fallbacks: other MIME types, no MIME (inferred from the name), and the Files / Downloads collections. */
    private void cr3Variants(String relativePath) {
        String[][] tries = {
            {"images", "image/x-canon-cr3"}, {"images", "image/x-canon-cr2"}, {"images", "image/*"},
            {"images", ""}, {"images", "application/octet-stream"}, {"images", "image/cr3"},
            {"files", "image/x-canon-cr3"}, {"files", ""}, {"files", "application/octet-stream"},
            {"downloads", "image/x-canon-cr3"}, {"downloads", "application/octet-stream"},
        };
        int i = 0;
        for (String[] t : tries) {
            i++;
            String label = "CR3[" + t[0] + "," + (t[1].isEmpty() ? "none" : t[1]) + "]";
            try {
                Uri coll = t[0].equals("images") ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : t[0].equals("files") ? MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                String rel = t[0].equals("downloads") ? "Download/Hoard/mediastore-probe/" : relativePath;
                insertInto(coll, rel, "IMG_000" + i + ".CR3", "CR3", t[1].isEmpty() ? null : t[1], label);
            } catch (Throwable e) { Log.i(TAG, "RESULT " + label + " insert=FAILED " + e); }
        }
    }

    private void insertInto(Uri coll, String relativePath, String name, String ext, String mime, String label) throws Exception {
        ContentResolver cr = getContentResolver();
        byte[] data = synthetic(ext);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        if (mime != null) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(coll, v);
        if (uri == null) { Log.i(TAG, "RESULT " + label + " insert=NULL"); return; }
        try (OutputStream out = cr.openOutputStream(uri, "w")) { out.write(data); }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        try (Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
            if (c != null && c.moveToFirst())
                Log.i(TAG, "RESULT " + label + " insert=OK uri=" + uri + " storedMime=" + c.getString(0) + " name="
                        + c.getString(1) + " rel=" + c.getString(2));
        }
        // Hand-off as Hoard would send it: a read grant on exactly this URI, to another app.
        android.content.Intent i = new android.content.Intent().setClassName("dev.hfps.probe.reader", "dev.hfps.probe.reader.ReaderActivity")
                .setDataAndType(uri, mime == null ? "*/*" : mime).putExtra("label", label)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        Thread.sleep(1500);
    }

    private void insert(String relativePath, String ext, String mime) throws Exception {
        ContentResolver cr = getContentResolver();
        byte[] data = ext.equals("JPG") ? jpeg() : synthetic(ext);
        String name = "DSC0001-" + ext.toLowerCase() + "." + ext;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = cr.insert(coll, v);
        if (uri == null) { Log.i(TAG, "RESULT " + ext + " insert=NULL"); return; }
        try (OutputStream out = cr.openOutputStream(uri, "w")) { out.write(data); }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        String stored = "?", storedName = "?", rel = "?"; long size = -1;
        try (Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                stored = c.getString(0); storedName = c.getString(1); size = c.getLong(2); rel = c.getString(3);
            }
        }
        Log.i(TAG, "RESULT " + ext + " insert=OK uri=" + uri + " requestedMime=" + mime + " storedMime=" + stored
                + " name=" + storedName + " rel=" + rel + " size=" + size + " written=" + data.length);
    }

    private static byte[] jpeg() {
        Bitmap bm = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        bm.eraseColor(Color.rgb(90, 120, 200));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    /** A file with the format's magic bytes, padded to 256 KiB with a deterministic pattern. */
    private static byte[] synthetic(String ext) {
        byte[] data = new byte[256 * 1024 + ext.hashCode() % 1000 + 1000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + 7);
        byte[] head;
        switch (ext) {
            case "CR3": head = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' ', 0, 0, 0, 1, 'c', 'r', 'x', ' ', 'i', 's', 'o', 'm'}; break;
            case "RAF": head = "FUJIFILMCCD-RAW 0201FF383501".getBytes(StandardCharsets.US_ASCII); break;
            case "ORF": head = new byte[]{'I', 'I', 'R', 'O', 8, 0, 0, 0}; break;
            case "RW2": head = new byte[]{'I', 'I', 'U', 0, 8, 0, 0, 0}; break;
            case "NEF": head = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8}; break;
            default: head = new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0}; break; // ARW, DNG: little-endian TIFF
        }
        System.arraycopy(head, 0, data, 0, head.length);
        return data;
    }
}
