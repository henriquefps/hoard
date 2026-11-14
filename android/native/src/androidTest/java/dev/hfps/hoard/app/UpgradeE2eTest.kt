package dev.hfps.hoard.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.data.legacy.LegacyDatabase
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.SmbConnector
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in (`-e hoardUpgradeE2e true`), on the same-id build installed OVER a Capacitor debug app that has real data,
 * after its first launch imported it (docs/testing/native-2a.md). Checks, in the upgraded app's own process:
 * every Capacitor row arrived unchanged; the Keystore passwords the Capacitor app saved still decrypt; and with them
 * the imported machines still sign in to their (test) Samba.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeE2eTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theUpgradeKeptEverything() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e hoardUpgradeE2e true", args.getString("hoardUpgradeE2e") == "true")
        assertThat(context.packageName).isEqualTo("dev.hfps.hoard")
        val container = (context as HoardApplication).container
        val target = container.database.openHelper.writableDatabase

        val record = runBlocking { container.database.legacyImports().find(CapacitorImporter.SOURCE) }
        assertWithMessage("the first launch imported").that(record?.status).isEqualTo("imported")

        val legacyFile = context.getDatabasePath(LegacyDatabase.FILE_NAME)
        SQLiteDatabase.openDatabase(legacyFile.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
            assertThat(legacy.version).isEqualTo(7)
            for (table in CapacitorImporter.TABLES) {
                assertWithMessage(table).that(target.rows(table)).isEqualTo(legacy.rows(table))
            }
        }

        val credentials = CredentialStore(context)
        val machines = target.query("SELECT id, server_guid FROM machines").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        assertThat(machines).isNotEmpty()
        for ((id, guid) in machines) {
            assertWithMessage("password of $id").that(credentials.has(id)).isTrue()
            if (guid == null) continue // never connected in the Capacitor app
            // Its first address, as route detection would try it; the pinned GUID must match (ADR 0002).
            val (host, port) = target.query(
                "SELECT host, port FROM machine_addresses WHERE machine_id = ? ORDER BY sort_order LIMIT 1",
                arrayOf(id),
            ).use { c -> c.moveToFirst(); c.getString(0) to c.getInt(1) }
            val handle = runBlocking { SmbConnector.open(host, port, guid, 10_000) { credentials.get(id) } }
            handle.use { assertThat(it.serverGuid).isEqualTo(guid) }
        }
    }
}
