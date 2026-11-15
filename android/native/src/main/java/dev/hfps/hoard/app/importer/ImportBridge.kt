package dev.hfps.hoard.app.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.hfps.hoard.app.smb.SmbGateway
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.data.legacy.ImportOutcome
import dev.hfps.hoard.data.legacy.ImportProgress
import dev.hfps.hoard.data.legacy.LegacyDatabase
import dev.hfps.hoard.smb.bridge.ImportBridgeContract
import dev.hfps.hoard.smb.bridge.ImportBridgeContract.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What Hoard 1's bridge says about its data before anything is copied (`settings.json`). */
data class BridgeSummary(
    val appVersion: String,
    val bridgeVersion: Int,
    val schemaVersion: Int,
    val counts: Map<String, Int>,
) {
    companion object {
        fun parse(json: String): BridgeSummary {
            val o = JSONObject(json)
            val counts = o.optJSONObject(Keys.COUNTS)
            return BridgeSummary(
                appVersion = o.optString(Keys.APP_VERSION),
                bridgeVersion = o.optInt(Keys.BRIDGE_VERSION),
                schemaVersion = o.optInt(Keys.SCHEMA_VERSION),
                counts = counts?.keys()?.asSequence()?.associateWith { counts.getInt(it) }.orEmpty(),
            )
        }
    }
}

/** What the first launch of the side-by-side build can offer (ADR 0019 "Side-by-side test build"). */
sealed interface BridgeOffer {
    /** Hoard 1.11+ answers: import its data now. */
    data class Available(val summary: BridgeSummary) : BridgeOffer

    /** Hoard 1 is installed but older than the bridge (1.11.0): update it first. */
    data class NeedsUpdate(val installedVersion: String) : BridgeOffer

    /** Hoard 1 is there but refuses this build: they are not signed with the same key (debug next to release). */
    data object Refused : BridgeOffer
}

/** The first launch's view of the bridge; the real one is [ImportBridge], tests give a fake. */
interface BridgeSource {
    /** Null when there is nothing to offer: Hoard 1's own id, already imported or decided, or no Hoard 1. */
    suspend fun offer(): BridgeOffer?

    /** Copies Hoard 1's snapshot and runs the importer on it. */
    suspend fun import(onProgress: (ImportProgress) -> Unit): ImportOutcome

    /** Machines that came over with a user name and no password here: asked for once ([PasswordsAfterImport]). */
    suspend fun machinesWithoutPassword(): List<String>
}

/**
 * Hoard Next's side of the import bridge: finds Hoard 1's provider, reads its summary, copies its database snapshot
 * into this app's no-backup folder and runs the same [CapacitorImporter] the in-place upgrade runs. The copy is deleted
 * whatever happens; nothing is ever written to Hoard 1.
 */
class ImportBridge(
    private val context: Context,
    private val database: HoardDatabase,
    private val settings: SettingsStore,
    private val gateway: SmbGateway,
) : BridgeSource {
    private val resolver get() = context.contentResolver

    override suspend fun offer(): BridgeOffer? = withContext(Dispatchers.IO) {
        // The same-id build (and the future swap) imports Hoard 1's file in place.
        if (context.packageName == ImportBridgeContract.HOARD1_PACKAGE) return@withContext null
        if (database.legacyImports().find(CapacitorImporter.SOURCE) != null) return@withContext null
        if (context.getDatabasePath(LegacyDatabase.FILE_NAME).exists()) return@withContext null
        val pm = context.packageManager
        val provider = pm.resolveContentProvider(ImportBridgeContract.AUTHORITY, 0)
        if (provider == null || provider.packageName != ImportBridgeContract.HOARD1_PACKAGE) {
            val installed = runCatching { pm.getPackageInfo(ImportBridgeContract.HOARD1_PACKAGE, 0) }.getOrNull()
            return@withContext installed?.let { BridgeOffer.NeedsUpdate(it.versionName.orEmpty()) }
        }
        try {
            val json = resolver.openInputStream(Uri.parse(ImportBridgeContract.uri(ImportBridgeContract.PATH_SETTINGS)))?.use { it.readBytes().decodeToString() }
            if (json == null) BridgeOffer.Refused else BridgeOffer.Available(BridgeSummary.parse(json))
        } catch (e: SecurityException) {
            Log.w(TAG, "Hoard refused the bridge: ${e.message}")
            BridgeOffer.Refused
        } catch (e: Exception) {
            Log.w(TAG, "bridge summary failed", e)
            BridgeOffer.Refused
        }
    }

    override suspend fun import(onProgress: (ImportProgress) -> Unit): ImportOutcome = withContext(Dispatchers.IO) {
        // Apart from the importer's own work folder: it snapshots its source into "legacy-import/<name>".
        val dir = File(context.noBackupFilesDir, "legacy-import-bridge").apply { mkdirs() }
        val copy = File(dir, LegacyDatabase.FILE_NAME)
        try {
            val summary = runCatching {
                resolver.openInputStream(Uri.parse(ImportBridgeContract.uri(ImportBridgeContract.PATH_SETTINGS)))?.use { BridgeSummary.parse(it.readBytes().decodeToString()) }
            }.getOrNull()
            if (summary != null && summary.bridgeVersion > ImportBridgeContract.VERSION) {
                return@withContext ImportOutcome.Failed(ImportFailure.TooNew, "bridge version ${summary.bridgeVersion}")
            }
            onProgress(ImportProgress(null, 0, CapacitorImporter.TABLES.size))
            val stream = resolver.openInputStream(Uri.parse(ImportBridgeContract.uri(ImportBridgeContract.PATH_DATABASE)))
                ?: return@withContext ImportOutcome.Failed(ImportFailure.Unreadable, "Hoard gave no data")
            stream.use { input -> copy.outputStream().use { input.copyTo(it) } }
            val outcome = CapacitorImporter(database, copy, File(context.noBackupFilesDir, "legacy-import")).importIfNeeded(onProgress)
            if (outcome is ImportOutcome.Imported) {
                PasswordsAfterImport.set(settings, machinesWithoutPassword())
            }
            outcome
        } catch (e: SecurityException) {
            ImportOutcome.Failed(ImportFailure.Unreadable, e.message.orEmpty())
        } catch (e: java.io.IOException) {
            ImportOutcome.Failed(ImportFailure.Other, e.message.orEmpty())
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    override suspend fun machinesWithoutPassword(): List<String> = withContext(Dispatchers.IO) {
        database.machines().all().filter { it.machine.username.isNotBlank() && !gateway.hasCredentials(it.machine.id) }.map { it.machine.id }
    }

    companion object {
        private const val TAG = "HoardImportBridge"

        /** True on a build where the bridge applies (any id but Hoard 1's own). */
        fun applies(context: Context): Boolean = context.packageName != ImportBridgeContract.HOARD1_PACKAGE
    }
}

/**
 * Machines whose password Hoard Next still has to ask for after a bridge import (the Keystore entries of Hoard 1
 * cannot leave it). A `settings` row (`passwordsAfterImport`, JSON array of machine ids): the Passwords screen removes a
 * machine once its password is saved or the user skips it; the Files tab shows a notice while the list is not empty.
 */
object PasswordsAfterImport {
    const val KEY = "passwordsAfterImport"

    fun parse(json: String?): List<String> = runCatching {
        val array = JSONArray(json ?: "[]")
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    suspend fun set(settings: SettingsStore, ids: List<String>) = settings.putJson(KEY, JSONArray(ids).toString())

    suspend fun remove(settings: SettingsStore, id: String) = set(settings, parse(settings.getJson(KEY)) - id)
}
