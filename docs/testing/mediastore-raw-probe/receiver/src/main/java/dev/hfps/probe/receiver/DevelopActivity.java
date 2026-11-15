package dev.hfps.probe.receiver;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/** Logs a hand-off (`adb logcat -s RevealProbe`): extras, flags, and what each URI's grant allows. */
public class DevelopActivity extends Activity {
    static final String TAG = "RevealProbe";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Intent i = getIntent();
        new Thread(() -> { report(i); finish(); }).start();
    }

    @SuppressWarnings("deprecation")
    private void report(Intent i) {
        int f = i.getFlags();
        Log.i(TAG, "INTENT action=" + i.getAction() + " title=" + i.getStringExtra("dev.hfps.reveal.extra.TITLE")
                + " read=" + ((f & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                + " write=" + ((f & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                + " persistable=" + ((f & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
                + " initial=" + i.getParcelableExtra("dev.hfps.reveal.extra.INITIAL_URI")
                + " cullingVersion=" + i.getIntExtra("dev.hfps.reveal.extra.CULLING_VERSION", 0)
                + " batch=" + i.getIntExtra("dev.hfps.reveal.extra.BATCH_INDEX", -1) + "/" + i.getIntExtra("dev.hfps.reveal.extra.BATCH_COUNT", -1)
                + " serverItems=" + (i.getParcelableArrayListExtra("dev.hfps.reveal.extra.SERVER_ITEMS") == null ? 0 : i.getParcelableArrayListExtra("dev.hfps.reveal.extra.SERVER_ITEMS").size()));
        Bundle culling = i.getBundleExtra("dev.hfps.reveal.extra.CULLING");
        if (culling != null) for (String k : culling.keySet()) {
            Bundle v = culling.getBundle(k);
            Log.i(TAG, "CULLING " + k + " rating=" + v.getInt("rating") + " flag=" + v.getString("flag") + " keys=" + new ArrayList<>(v.keySet()));
        }
        ClipData clip = i.getClipData();
        if (clip == null) { Log.i(TAG, "CLIP none"); return; }
        for (int n = 0; n < clip.getItemCount(); n++) {
            Uri uri = clip.getItemAt(n).getUri();
            long read = 0; String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[65536]; int r;
                while ((r = in.read(buf)) > 0) read += r;
            } catch (Throwable t) { err = t.getClass().getSimpleName(); }
            String persist;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); persist = "TAKEN"; }
            catch (Throwable t) { persist = "refused(" + t.getClass().getSimpleName() + ")"; }
            String write;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wa")) { write = "ALLOWED"; }
            catch (Throwable t) { write = "refused(" + t.getClass().getSimpleName() + ")"; }
            Log.i(TAG, "ITEM " + n + " " + uri + " type=" + getContentResolver().getType(uri) + " read=" + read + (err == null ? "" : " readError=" + err)
                    + " persistable=" + persist + " write=" + write);
        }
    }
}
