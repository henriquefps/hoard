package dev.hfps.hoard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import dev.hfps.hoard.app.ui.HoardApp
import dev.hfps.hoard.app.ui.shell.ShellRequests
import dev.hfps.hoard.app.ui.transfers.NotificationsPermission
import dev.hfps.hoard.smb.share.IncomingShares
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** MediaStore's trash, restore and delete consents (ADR 0023, the phone's trash): one at a time. */
    private var consent: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consent?.complete(result.resultCode == RESULT_OK)
        consent = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HoardApplication).container
        // Transfers and streams run as foreground services: ask once per run for their notification (Android 13+).
        NotificationsPermission.ask = {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        dev.hfps.hoard.app.trash.MediaConsent.launcher = { intent, result ->
            consent?.complete(false)
            consent = result
            runCatching { consentLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(intent).build()) }
                .onFailure { result.complete(false); consent = null }
        }
        // Launched from another app's share sheet (ADR 0007, share in).
        if (savedInstanceState == null) receiveShare(intent)
        setContent {
            HoardApp(container) { dark ->
                // Transparent bars; their icons follow the theme the user picked, not only the system's.
                DisposableEffect(dark) {
                    val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }
            }
        }
    }

    /** `singleTask`: a share while Hoard is running arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.hfps.hoard.app.data.AppLanguage.wrap(newBase))
    }

    override fun onDestroy() {
        consent?.complete(false)
        consent = null
        dev.hfps.hoard.app.trash.MediaConsent.launcher = null
        super.onDestroy()
    }

    override fun onStop() {
        (application as HoardApplication).container.onStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        (application as HoardApplication).container.onResume()
    }

    private fun receiveShare(intent: Intent?) {
        if (!IncomingShares.isShare(intent)) return
        IncomingShares.init(this)
        val share = intent!!
        setIntent(Intent(Intent.ACTION_MAIN))
        lifecycleScope.launch {
            val received = withContext(Dispatchers.IO) { runCatching { IncomingShares.receive(applicationContext, share) }.getOrNull() }
            if (received != null) ShellRequests.openSave.value = true
        }
    }
}
