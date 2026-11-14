package dev.hfps.hoard.app

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.legacy.LegacyDatabase
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first launch after the upgrade, end to end in the app: a Capacitor database where the Capacitor app keeps it,
 * no native database yet → the import screen with the counts → the Files tab with the imported machines.
 * Replaces this install's databases, so it never runs on the same-id build (that one holds the E2E data).
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun importsThenShowsTheFilesTab() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeFalse("same-id build: the E2E data lives here", context.packageName == "dev.hfps.hoard")
        context.deleteDatabase(HoardDatabase.FILE_NAME)
        DeviceFixtures.copy("hoard-v7.db", context.getDatabasePath(LegacyDatabase.FILE_NAME))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("3 machines")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("4 favorites").assertExists()
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("NAS")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("192.168.1.10 and 2 more addresses").assertExists()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Lisboa"))
            compose.onNodeWithText("NAS · photos · 2026/Lisboa").assertExists()
        }
    }
}
