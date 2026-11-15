package dev.hfps.probe.reader;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import java.io.InputStream;

/** Another app with READ_MEDIA_IMAGES: finds the writer's files in MediaStore.Images and reads each one. */
public class ReaderActivity extends Activity {
    static final String TAG = "ProbeReader";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String dir = getIntent().getStringExtra("dir");
        if (dir == null) dir = "Pictures/Hoard/mediastore-probe/";
        final String relativePath = dir;
        final Uri granted = getIntent().getData();
        if (granted != null) {
            String label = getIntent().getStringExtra("label");
            new Thread(() -> {
                long read = 0; String err = null;
                try (InputStream in = getContentResolver().openInputStream(granted)) {
                    byte[] buf = new byte[65536]; int r;
                    while ((r = in.read(buf)) > 0) read += r;
                } catch (Throwable t) { err = t.toString(); }
                Log.i(TAG, "GRANTED " + label + " uri=" + granted + " read=" + read + (err == null ? " open=OK" : " open=FAILED " + err));
                finish();
            }).start();
            return;
        }
        new Thread(() -> { run(relativePath); finish(); }).start();
    }

    private void run(String relativePath) {
        ContentResolver cr = getContentResolver();
        boolean files = getIntent().getBooleanExtra("files", false);
        Uri coll = files ? MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Log.i(TAG, "collection=" + coll);
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.OWNER_PACKAGE_NAME};
        int n = 0;
        try (Cursor c = cr.query(coll, proj, MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{relativePath}, MediaStore.MediaColumns.DISPLAY_NAME)) {
            while (c != null && c.moveToNext()) {
                n++;
                long id = c.getLong(0);
                Uri uri = ContentUris.withAppendedId(coll, id);
                long size = c.getLong(3);
                long read = 0; String err = null;
                try (InputStream in = cr.openInputStream(uri)) {
                    byte[] buf = new byte[65536]; int r;
                    while ((r = in.read(buf)) > 0) read += r;
                } catch (Throwable t) { err = t.toString(); }
                Log.i(TAG, "RESULT name=" + c.getString(1) + " mime=" + c.getString(2) + " owner=" + c.getString(4)
                        + " uri=" + uri + " size=" + size + " read=" + read
                        + (err == null ? (read == size ? " open=OK" : " open=SIZE_MISMATCH") : " open=FAILED " + err));
            }
        } catch (Throwable t) { Log.i(TAG, "QUERY FAILED " + t); }
        Log.i(TAG, "DONE visible=" + n);
    }
}
