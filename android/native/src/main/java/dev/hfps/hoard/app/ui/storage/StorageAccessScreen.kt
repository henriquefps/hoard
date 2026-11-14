package dev.hfps.hoard.app.ui.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.browse.findActivity
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.location.StorageAccess
import kotlinx.coroutines.launch

/** "All files access" (ADR 0005): Android 11+ is a Settings switch, Android 10 and older the storage permissions. */
object StorageAccessIntents {
    fun granted(context: Context) = runCatching { StorageAccess.granted(context) }.getOrDefault(false)

    /** Opens Android's switch for Hoard (Android 11+). Older versions ask through [rememberStoragePermission]. */
    fun open(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            val intent = if (forApp.resolveActivity(context.packageManager) != null) forApp else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching { (context.findActivity() ?: context).startActivity(intent.addFlags(if (context.findActivity() == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0)) }
        }
    }
}

/** Asks for all files access: the Settings switch on Android 11+, the runtime permissions before. */
@Composable
fun rememberStoragePermission(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StorageAccessIntents.open(context)
        } else {
            @Suppress("DEPRECATION")
            launcher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}

/**
 * First run (and from the Files tab's card): why Hoard asks for all files access, then Android's own page for it.
 * "Not now" keeps SMB working; the screen shows on its own only once (`storageIntroSeen`).
 */
@Composable
fun StorageAccessScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(StorageAccessIntents.granted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = StorageAccessIntents.granted(context) }
    val ask = rememberStoragePermission { granted = StorageAccessIntents.granted(context) }
    val runtime = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    val done: () -> Unit = {
        scope.launch {
            container.appSettings.setStorageIntroSeen(true)
            onDone()
        }
    }
    HfpsScaffold(topBar = { HfpsTopBar(stringResource(R.string.t_storage_access_page_title), onBack = done) }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(HfpsTheme.colors.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(if (granted) PhosphorRegular.CheckCircle else PhosphorRegular.HardDrives, null, Modifier.size(32.dp), tint = HfpsTheme.colors.primary)
                }
                Text(
                    stringResource(if (granted) R.string.t_storage_access_granted_title else R.string.t_storage_access_title),
                    style = HfpsTheme.typography.title,
                    color = HfpsTheme.colors.cardForeground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(if (granted) R.string.t_storage_access_granted_body else R.string.t_storage_access_body),
                    style = HfpsTheme.typography.body,
                    color = HfpsTheme.colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
                if (!granted) {
                    Point(PhosphorRegular.FolderSimple, R.string.t_storage_access_point1_title, R.string.t_storage_access_point1_body)
                    Point(PhosphorRegular.Usb, R.string.t_storage_access_point2_title, R.string.t_storage_access_point2_body)
                    Point(PhosphorRegular.ShieldCheck, R.string.t_storage_access_point3_title, R.string.t_storage_access_point3_body)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val steps = if (runtime) listOf(R.string.t_storage_access_step_runtime) else listOf(R.string.t_storage_access_step1, R.string.t_storage_access_step2, R.string.t_storage_access_step3)
                        steps.forEachIndexed { i, step ->
                            Text("${i + 1}. " + stringResource(step), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
                        }
                    }
                    PrimaryButton(
                        stringResource(if (runtime) R.string.t_storage_access_allow_runtime else R.string.t_storage_access_open_settings),
                        ask,
                        icon = PhosphorRegular.Gear,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GhostButton(stringResource(R.string.t_storage_access_not_now), done, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.t_storage_access_smb_note), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, textAlign = TextAlign.Center)
                } else {
                    PrimaryButton(stringResource(R.string.t_storage_access_continue), done, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: Int, body: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HfpsTheme.colors.muted.copy(alpha = 0.5f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = HfpsTheme.colors.primary)
        Column {
            Text(stringResource(title), style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
            Text(stringResource(body), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
    }
}
