package dev.hfps.hoard.smb.handoff

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/** Takes every persistable read grant on the intent's ClipData (Hoard's burst setup sends them), then finishes. */
class TakeGrantsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clip = intent.clipData
        var taken = 0
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                if (runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess) taken++
            }
        }
        Log.i("HoardBurst", "took $taken grants")
        finish()
    }
}
