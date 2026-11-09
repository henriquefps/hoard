package dev.hfps.hoard.smb

import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Native side of the HoardSmb contract (`src/plugins/smb/definitions.ts`).
 *
 * STUB: every method rejects with code `UNKNOWN` ("not implemented"). The native task fills it in
 * with smbj (SMB2/3), the Android Keystore (credentials), the Storage Access Framework (destination
 * folders) and a foreground service (transfers). Keep method names, option names, result shapes,
 * error codes and event names exactly as in `definitions.ts`; change all three implementations
 * (definitions.ts, web.ts, this file) together.
 *
 * Events to emit with `notifyListeners`: `transferProgress`, `networkChanged`.
 */
@CapacitorPlugin(name = "HoardSmb")
class HoardSmbPlugin : Plugin() {

    private fun notImplemented(call: PluginCall) {
        call.reject("HoardSmb.${call.methodName} is not implemented yet", ErrorCode.UNKNOWN.name)
    }

    // Reachability and discovery
    @PluginMethod fun probe(call: PluginCall) = notImplemented(call)
    @PluginMethod fun discover(call: PluginCall) = notImplemented(call)

    // Credentials (Android Keystore)
    @PluginMethod fun setCredentials(call: PluginCall) = notImplemented(call)
    @PluginMethod fun hasCredentials(call: PluginCall) = notImplemented(call)
    @PluginMethod fun deleteCredentials(call: PluginCall) = notImplemented(call)

    // Sessions
    @PluginMethod fun connect(call: PluginCall) = notImplemented(call)
    @PluginMethod fun disconnect(call: PluginCall) = notImplemented(call)

    // Browsing
    @PluginMethod fun listShares(call: PluginCall) = notImplemented(call)
    @PluginMethod fun list(call: PluginCall) = notImplemented(call)
    @PluginMethod fun stat(call: PluginCall) = notImplemented(call)
    @PluginMethod fun createFolder(call: PluginCall) = notImplemented(call)
    @PluginMethod fun rename(call: PluginCall) = notImplemented(call)
    @PluginMethod fun delete(call: PluginCall) = notImplemented(call)

    // Destinations (Storage Access Framework)
    @PluginMethod fun pickDestinationFolder(call: PluginCall) = notImplemented(call)
    @PluginMethod fun listDestinationFolders(call: PluginCall) = notImplemented(call)
    @PluginMethod fun releaseDestinationFolder(call: PluginCall) = notImplemented(call)

    // Transfers
    @PluginMethod fun startDownload(call: PluginCall) = notImplemented(call)
    @PluginMethod fun cancelTransfer(call: PluginCall) = notImplemented(call)
    @PluginMethod fun getTransfers(call: PluginCall) = notImplemented(call)
    @PluginMethod fun acknowledgeTransfers(call: PluginCall) = notImplemented(call)

    // Viewing
    @PluginMethod fun openFile(call: PluginCall) = notImplemented(call)
    @PluginMethod fun thumbnail(call: PluginCall) = notImplemented(call)
}
