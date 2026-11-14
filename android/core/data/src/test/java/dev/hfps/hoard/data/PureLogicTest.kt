package dev.hfps.hoard.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.legacy.LegacyMigrations
import dev.hfps.hoard.data.legacy.SqlStatements
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PureLogicTest {
    @Test fun splitsStatementsOutsideLiteralsAndComments() {
        val sql = """
            CREATE TABLE a (x TEXT DEFAULT 'one; two');
            -- a comment; with a semicolon
            INSERT INTO a VALUES ('it''s; fine'); /* block; comment */
            SELECT "odd;name" FROM a
        """
        assertThat(SqlStatements.split(sql)).containsExactly(
            "CREATE TABLE a (x TEXT DEFAULT 'one; two')",
            "INSERT INTO a VALUES ('it''s; fine')",
            "SELECT \"odd;name\" FROM a",
        ).inOrder()
    }

    @Test fun theCopiedMigrationsAreTheCapacitorOnes() {
        assertThat(LegacyMigrations.ALL.map { it.version }).isEqualTo((1..LegacyMigrations.LATEST_VERSION).toList())
        // Migration 4's JSON-building expression survives the Kotlin copy and the split.
        val v4 = SqlStatements.split(LegacyMigrations.ALL.single { it.version == 4 }.sql)
        assertThat(v4).hasSize(7)
        assertThat(v4[1]).contains("""'["' || replace(replace(remote_path, '\', '\\'), '"', '\"') || '"]'""")
    }

    @Test fun settingsAreJsonValuesLikeTheCapacitorApp() {
        assertThat(SettingsStore.encodeString("dark")).isEqualTo("\"dark\"")
        assertThat(SettingsStore.encodeString("a \"b\" \\ c")).isEqualTo("\"a \\\"b\\\" \\\\ c\"")
        assertThat(SettingsStore.decodeString(SettingsStore.encodeString("a \"b\" \\ c"))).isEqualTo("a \"b\" \\ c")
        assertThat(SettingsStore.decodeString("true")).isNull()
        assertThat(SettingsStore.decodeString("{\"key\":\"modified\"}")).isNull()
        assertThat(SettingsStore.decodeString("not json")).isNull()
    }
}
