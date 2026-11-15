package dev.hfps.hoard.app

import android.content.ClipboardManager
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.files.InlineSpan
import dev.hfps.hoard.app.files.LinkTarget
import dev.hfps.hoard.app.files.inlineSpans
import dev.hfps.hoard.app.files.parseMarkdown
import dev.hfps.hoard.app.files.resolveLink
import dev.hfps.hoard.app.files.stripInline
import dev.hfps.hoard.app.importer.BridgeOffer
import dev.hfps.hoard.app.importer.BridgeSource
import dev.hfps.hoard.app.importer.BridgeSummary
import dev.hfps.hoard.app.importer.ImportBridge
import dev.hfps.hoard.app.importer.PasswordsAfterImport
import dev.hfps.hoard.app.smb.SmbGateway
import dev.hfps.hoard.app.ui.cull.betterOfPair
import dev.hfps.hoard.app.ui.importer.ImportScreen
import dev.hfps.hoard.app.ui.importer.ImportUiState
import dev.hfps.hoard.app.ui.importer.ImportViewModel
import dev.hfps.hoard.app.ui.importer.OfferActions
import dev.hfps.hoard.app.ui.machines.PasswordItem
import dev.hfps.hoard.app.ui.machines.PasswordResult
import dev.hfps.hoard.app.ui.machines.PasswordsScreen
import dev.hfps.hoard.app.ui.machines.accountOf
import dev.hfps.hoard.app.ui.machines.passwordResultOf
import dev.hfps.hoard.app.ui.machines.pendingPasswords
import dev.hfps.hoard.app.ui.shell.usesTransfersSheet
import dev.hfps.hoard.app.ui.transfers.TransfersSheet
import dev.hfps.hoard.app.ui.viewer.EditLayout
import dev.hfps.hoard.app.ui.viewer.ImageSource
import dev.hfps.hoard.app.ui.viewer.MarkdownHost
import dev.hfps.hoard.app.ui.viewer.MarkdownView
import dev.hfps.hoard.app.ui.viewer.editLayoutShown
import dev.hfps.hoard.app.ui.viewer.imageSource
import dev.hfps.hoard.app.ui.viewer.splitFits
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.data.TransferEntity
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.data.legacy.ImportOutcome
import dev.hfps.hoard.data.legacy.ImportProgress
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.bridge.ImportBridgeContract
import dev.hfps.hoard.smb.transfer.TransferStates
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2d (ADR 0019): the "Partly" rows closed (Markdown images and "Copy code", the split editor, the transfers
 * sheet, compare's focus score) and the side-by-side build's import bridge, the import offer and the Passwords step.
 * Goldens under `src/test/screenshots/`, copied to docs/testing/native-2d/.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Phase2dTest {
    @get:Rule val compose = createComposeRule()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun shoot(name: String, qualifiers: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { HfpsTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val PHONE = ShellScreenshotTest.PHONE
    private val TABLET = ShellScreenshotTest.TABLET

    // --- Markdown: images, links, code ---------------------------------------------------------------------

    @Test fun imagesAndLinkedBadgesAreInlineSpans() {
        assertThat(inlineSpans("![A cat](img/cat.png \"title\")")).containsExactly(InlineSpan("A cat", image = "img/cat.png"))
        val badge = inlineSpans("[![CI](https://x/badge.png)](https://x/actions) and [docs](docs/a.md)")
        assertThat(badge.first()).isEqualTo(InlineSpan("CI", image = "https://x/badge.png", link = "https://x/actions"))
        assertThat(badge.last()).isEqualTo(InlineSpan("docs", link = "docs/a.md"))
        assertThat(stripInline("see ![chart](c.png) here")).isEqualTo("see chart here")
    }

    @Test fun imagesResolveByHoard1Rules() {
        assertThat(imageSource("notes/today.md", "img/a.jpg")).isEqualTo(ImageSource.Share("notes/img/a.jpg"))
        assertThat(imageSource("notes/today.md", "../b.PNG")).isEqualTo(ImageSource.Share("b.PNG"))
        assertThat(imageSource("notes/today.md", "/root.webp")).isEqualTo(ImageSource.Share("root.webp"))
        assertThat(imageSource("notes/today.md", "https://example.com/x.png")).isEqualTo(ImageSource.Web("https://example.com/x.png"))
        assertThat(imageSource("a.md", "data:image/png;base64,AAAA")).isEqualTo(ImageSource.Data("data:image/png;base64,AAAA"))
        // No thumbnail for these types, above the root, other schemes, backslashes: not shown.
        assertThat(imageSource("a.md", "diagram.svg")).isEqualTo(ImageSource.Blocked)
        assertThat(imageSource("a.md", "../up.png")).isEqualTo(ImageSource.Blocked)
        assertThat(imageSource("a.md", "file:///etc/x.png")).isEqualTo(ImageSource.Blocked)
        assertThat(imageSource("a.md", "mailto:me@x")).isEqualTo(ImageSource.Blocked)
        assertThat(resolveLink("a.md", "dir\\x.png")).isEqualTo(LinkTarget.Blocked)
    }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3F7FBF.toInt()) }.asImageBitmap()

    private val doc = """
        # Trip notes

        ![Lisbon](photos/lisbon.jpg)

        [![build](https://example.com/badge.png)](https://example.com) ![missing](gone.png)

        ```kotlin
        // pick the sharpest
        fun best(shots: List<Shot>) = shots.maxBy { it.score }
        ```
    """.trimIndent()

    private val markdown: @Composable () -> Unit = {
        MarkdownView(
            remember { parseMarkdown(doc) },
            14,
            false,
            MarkdownHost(loadImage = { src ->
                when (src) {
                    "photos/lisbon.jpg" -> bitmap(600, 300)
                    "https://example.com/badge.png" -> bitmap(90, 20)
                    else -> null
                }
            }),
            rememberLazyListState(),
        )
    }

    @Test fun markdownShowsImagesAndBrokenOnesWithTheirAlt() {
        compose.setContent { HfpsTheme(darkTheme = false) { markdown() } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("missing").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("md-image-broken").assertIsDisplayed()
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("md-image")).fetchSemanticsNodes()).hasSize(2)
    }

    @Test fun copyCodePutsTheBlockOnTheClipboard() {
        compose.setContent { HfpsTheme(darkTheme = false) { markdown() } }
        compose.onNodeWithTag("md-copy-code").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("// pick the sharpest\nfun best(shots: List<Shot>) = shots.maxBy { it.score }")
    }

    @Test fun markdownPhoneLight() = shoot("markdown_images_code_phone_light", PHONE, dark = false) { markdown() }

    @Test fun markdownPhoneDark() = shoot("markdown_images_code_phone_dark", PHONE, dark = true) { markdown() }

    // --- Split editor, transfers sheet, compare -----------------------------------------------------------------

    @Test fun splitOnlyOnWideWindows() {
        assertThat(splitFits(839)).isFalse()
        assertThat(splitFits(840)).isTrue()
        assertThat(editLayoutShown(null, wide = true)).isEqualTo(EditLayout.Split)
        assertThat(editLayoutShown(null, wide = false)).isEqualTo(EditLayout.Editor)
        assertThat(editLayoutShown(EditLayout.Split, wide = false)).isEqualTo(EditLayout.Editor)
        assertThat(editLayoutShown(EditLayout.Preview, wide = false)).isEqualTo(EditLayout.Preview)
        assertThat(editLayoutShown(EditLayout.Editor, wide = true)).isEqualTo(EditLayout.Editor)
    }

    @Test fun transfersSheetOnPhonesScreenOnTablets() {
        assertThat(usesTransfersSheet(390.dp)).isTrue()
        assertThat(usesTransfersSheet(599.dp)).isTrue()
        assertThat(usesTransfersSheet(600.dp)).isFalse()
    }

    @Test fun compareCrownsTheRankingElseTheSharperFocus() {
        assertThat(betterOfPair("a", "b", listOf("b" to 0.9, "a" to 0.4), emptyMap())).isEqualTo("b")
        assertThat(betterOfPair("a", "b", listOf("a" to 0.5, "b" to 0.5), emptyMap())).isNull()
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 310.0, "b" to 120.0))).isEqualTo("a")
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 12.0, "b" to 99.0))).isEqualTo("b")
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 50.0))).isNull()
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 50.0, "b" to 50.0))).isNull()
    }

    private fun seedTransfers() = runBlocking {
        val dao = Samples2b.container.database.transfers()
        if (dao.all().isNotEmpty()) return@runBlocking
        fun row(id: String, state: String, name: String, done: Long, size: Long, items: Int, itemsDone: Int) = TransferEntity(
            id, "copy", "device", null, "", "[\"Download/$name\"]", "device", null, "", "DCIM/Backup", null, name, "ask", size, done,
            items, itemsDone, 0, 0, state, null, null, "2026-11-14T10:0$items:00Z",
            if (state == TransferStates.COMPLETED) "2026-11-14T10:30:00Z" else null,
        )
        dao.insert(row("s1", TransferStates.RUNNING, "clip.mp4", 40_000_000, 104_432_771, 1, 0))
        dao.insert(row("s2", TransferStates.COMPLETED, "DSC0001.JPG", 6_412_331, 6_412_331, 2, 2))
    }

    @Test fun transfersSheetPhone() {
        seedTransfers()
        RuntimeEnvironment.setQualifiers("+$PHONE")
        var all = 0
        compose.setContent { HfpsTheme(darkTheme = false) { Samples2b.Toasts { TransfersSheet(onDismiss = {}, onOpenAll = { all++ }, onOpenDestination = {}) } } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("clip.mp4").assertIsDisplayed() }.isSuccess }
        captureScreenRoboImage("src/test/screenshots/transfers_sheet_phone_light.png")
        compose.onNodeWithTag("transfers-open-all").performClick()
        assertThat(all).isEqualTo(1)
    }

    // --- Import bridge -----------------------------------------------------------------------------------------

    private class FakeBridge(var offer: BridgeOffer?, val outcome: ImportOutcome, val passwords: List<String> = emptyList()) : BridgeSource {
        var imports = 0
        override suspend fun offer() = offer
        override suspend fun import(onProgress: (ImportProgress) -> Unit): ImportOutcome {
            imports++
            onProgress(ImportProgress("machines", 0, 11))
            return outcome
        }
        override suspend fun machinesWithoutPassword() = passwords
    }

    private val summary = BridgeSummary("1.11.0", 1, 7, mapOf("machines" to 2, "favorites" to 3, "cull_decisions" to 1240, "transfers" to 9))
    private val record = dev.hfps.hoard.data.LegacyImportEntity("capacitor", "imported", 1, 7, """{"machines":2}""", "t")

    @Test fun theOfferImportsThroughTheBridgeThenAsksForPasswords() {
        val bridge = FakeBridge(BridgeOffer.Available(summary), ImportOutcome.Imported(record), passwords = listOf("m-nas", "m-pc"))
        var localRuns = 0
        val vm = ImportViewModel(bridge) { localRuns++; ImportOutcome.NothingToImport }
        assertThat(vm.state.value).isEqualTo(ImportUiState.Offer(BridgeOffer.Available(summary)))
        vm.importFromHoard()
        assertThat(bridge.imports).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(ImportUiState.Imported(mapOf("machines" to 2), passwords = 2))
        vm.finish()
        assertThat(vm.state.value).isEqualTo(ImportUiState.Passwords)
        vm.passwordsDone()
        assertThat(vm.state.value).isEqualTo(ImportUiState.Ready)
        assertThat(localRuns).isEqualTo(0)
    }

    @Test fun startingFreshRecordsNothingToImportAndRetryRepeatsTheBridge() {
        val fresh = ImportViewModel(FakeBridge(BridgeOffer.NeedsUpdate("1.10.1"), ImportOutcome.NothingToImport)) { ImportOutcome.NothingToImport }
        assertThat(fresh.state.value).isEqualTo(ImportUiState.Offer(BridgeOffer.NeedsUpdate("1.10.1")))
        fresh.startFresh()
        assertThat(fresh.state.value).isEqualTo(ImportUiState.Ready)

        val failing = FakeBridge(BridgeOffer.Available(summary), ImportOutcome.Failed(ImportFailure.Unreadable, "x"))
        val vm = ImportViewModel(failing) { ImportOutcome.NothingToImport }
        vm.importFromHoard()
        assertThat(vm.state.value).isInstanceOf(ImportUiState.Failed::class.java)
        vm.retry()
        assertThat(failing.imports).isEqualTo(2)

        // No offer (Hoard 1's own id, or nothing installed): the plain first launch.
        val none = ImportViewModel(FakeBridge(null, ImportOutcome.NothingToImport)) { ImportOutcome.NothingToImport }
        assertThat(none.state.value).isEqualTo(ImportUiState.Ready)
    }

    @Test fun theOfferScreenImportsOrStartsFresh() {
        var imports = 0
        var fresh = 0
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                ImportScreen(ImportUiState.Offer(BridgeOffer.Available(summary)), {}, {}, offer = OfferActions(import = { imports++ }, fresh = { fresh++ }))
            }
        }
        compose.onNodeWithText("Hoard 1.11.0 is on this phone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1240 culling decisions", substring = true).assertExists()
        compose.onNodeWithTag("import-from-hoard").performScrollTo().performClick()
        compose.onNodeWithTag("import-fresh").performScrollTo().performClick()
        assertThat(imports).isEqualTo(1)
        assertThat(fresh).isEqualTo(1)
    }

    @Test fun offerPhoneLight() = shoot("import_offer_phone_light", PHONE, dark = false) {
        ImportScreen(ImportUiState.Offer(BridgeOffer.Available(summary)), {}, {})
    }

    @Test fun offerPhoneDark() = shoot("import_offer_phone_dark", PHONE, dark = true) {
        ImportScreen(ImportUiState.Offer(BridgeOffer.Available(summary)), {}, {})
    }

    @Test fun offerNeedsUpdatePhoneLight() = shoot("import_offer_update_phone_light", PHONE, dark = false) {
        ImportScreen(ImportUiState.Offer(BridgeOffer.NeedsUpdate("1.10.1")), {}, {})
    }

    @Test fun importedThroughTheBridgePhoneLight() = shoot("import_done_bridge_phone_light", PHONE, dark = false) {
        ImportScreen(ImportUiState.Imported(Samples.counts, passwords = 2), {}, {})
    }

    /**
     * The real [ImportBridge] against a fake Hoard 1: a provider registered for `dev.hfps.hoard`, serving the v7
     * fixture database made by Hoard 1's own migrations. The import runs the real importer and lists the machines with
     * a user name for the Passwords step.
     */
    @Test fun theBridgeImportsHoard1sSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = HoardDatabase.open(context, null)
        val settings = SettingsStore(database.settings())
        val bridge = ImportBridge(context, database, settings, SmbGateway(context))
        assertThat(context.packageName).isNotEqualTo(ImportBridgeContract.HOARD1_PACKAGE)
        // Nothing installed: no offer.
        assertThat(bridge.offer()).isNull()
        // Hoard 1.10 (no bridge yet): update it.
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = ImportBridgeContract.HOARD1_PACKAGE; versionName = "1.10.1" })
        assertThat(bridge.offer()).isEqualTo(BridgeOffer.NeedsUpdate("1.10.1"))
        // Hoard 1.11: its provider answers.
        shadowOf(context.packageManager).addOrUpdateProvider(ProviderInfo().apply { authority = ImportBridgeContract.AUTHORITY; packageName = ImportBridgeContract.HOARD1_PACKAGE; name = "dev.hfps.hoard.bridge.ImportBridgeProvider" })
        val resolver = shadowOf(context.contentResolver)
        resolver.registerInputStreamSupplier(Uri.parse(ImportBridgeContract.uri(ImportBridgeContract.PATH_SETTINGS))) {
            """{"bridgeVersion":1,"appVersion":"1.11.0","schemaVersion":7,"counts":{"machines":2}}""".byteInputStream()
        }
        resolver.registerInputStreamSupplier(Uri.parse(ImportBridgeContract.uri(ImportBridgeContract.PATH_DATABASE))) {
            checkNotNull(javaClass.classLoader?.getResourceAsStream("legacy/hoard-v7.db"))
        }
        val offer = bridge.offer()
        assertThat(offer).isInstanceOf(BridgeOffer.Available::class.java)
        assertThat((offer as BridgeOffer.Available).summary.appVersion).isEqualTo("1.11.0")

        val outcome = bridge.import {}
        assertThat(outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        val machines = database.machines().all()
        assertThat(machines).isNotEmpty()
        assertThat(database.legacyImports().find(CapacitorImporter.SOURCE)!!.status).isEqualTo("imported")
        // Every fixture machine with a user name has no password here: the Passwords step asks for each.
        val withUser = machines.filter { it.machine.username.isNotBlank() }.map { it.machine.id }
        assertThat(withUser).isNotEmpty()
        assertThat(PasswordsAfterImport.parse(settings.getJson(PasswordsAfterImport.KEY))).containsExactlyElementsIn(withUser)
        // The copy is gone, and an imported install is not offered again.
        assertThat(java.io.File(context.noBackupFilesDir, "legacy-import-bridge").listFiles().orEmpty()).isEmpty()
        assertThat(bridge.offer()).isNull()
        database.close()
    }

    @Test fun aNewerBridgeIsRefused() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = HoardDatabase.open(context, null)
        val bridge = ImportBridge(context, database, SettingsStore(database.settings()), SmbGateway(context))
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse(ImportBridgeContract.uri(ImportBridgeContract.PATH_SETTINGS))) {
            """{"bridgeVersion":99,"appVersion":"9.0.0"}""".byteInputStream()
        }
        val outcome = bridge.import {}
        assertThat(outcome).isEqualTo(ImportOutcome.Failed(ImportFailure.TooNew, "bridge version 99"))
        assertThat(database.machines().all()).isEmpty()
        database.close()
    }

    // --- Passwords --------------------------------------------------------------------------------------------

    @Test fun passwordsLogic() {
        assertThat(PasswordsAfterImport.parse(null)).isEmpty()
        assertThat(PasswordsAfterImport.parse("not json")).isEmpty()
        assertThat(PasswordsAfterImport.parse("""["a","b"]""")).containsExactly("a", "b").inOrder()
        assertThat(accountOf("me", "HOME")).isEqualTo("HOME\\me")
        assertThat(accountOf("photos", null)).isEqualTo("photos")
        assertThat(passwordResultOf(ErrorCode.AUTH_FAILED)).isEqualTo(PasswordResult.Wrong)
        assertThat(passwordResultOf(ErrorCode.UNREACHABLE)).isEqualTo(PasswordResult.Offline)
        val items = pendingPasswords(listOf("m1"), listOf(Samples2b.machine))
        assertThat(items).containsExactly(PasswordItem("m1", "Test Samba", "desktop-tower", "hoard"))
        assertThat(pendingPasswords(listOf("gone"), listOf(Samples2b.machine))).isEmpty()
    }

    private val passwordItems = listOf(
        PasswordItem("m-nas", "NAS", null, "photos"),
        PasswordItem("m-pc", "PC", "desktop-tower", "HOME\\me"),
    )

    @Test fun savingAPasswordShowsTheResult() {
        val saved = ArrayList<Pair<String, String>>()
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                PasswordsScreen(passwordItems, onSave = { item, password ->
                    saved += item.id to password
                    if (password == "right") PasswordResult.Connected else PasswordResult.Wrong
                }, onSkip = {}, onDone = {})
            }
        }
        compose.onNodeWithTag("password-field-m-nas").performTextInput("wrong")
        compose.onNodeWithTag("password-save-m-nas").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NAS refused this password", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("passwords").performScrollToNode(androidx.compose.ui.test.hasTestTag("password-field-m-pc"))
        compose.onNodeWithTag("password-field-m-pc").performTextInput("right")
        compose.onNodeWithTag("passwords").performScrollToNode(androidx.compose.ui.test.hasTestTag("password-save-m-pc"))
        compose.onNodeWithTag("password-save-m-pc").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passwords").performScrollToNode(androidx.compose.ui.test.hasText("Saved. Connected to PC."))
        compose.onNodeWithText("Saved. Connected to PC.").assertIsDisplayed()
        assertThat(saved).containsExactly("m-nas" to "wrong", "m-pc" to "right").inOrder()
    }

    @Test fun passwordsPhoneLight() = shoot("passwords_phone_light", PHONE, dark = false) {
        PasswordsScreen(passwordItems, onSave = { _, _ -> PasswordResult.Connected }, onSkip = {}, onDone = {})
    }

    @Test fun passwordsTabletDark() = shoot("passwords_tablet_dark", TABLET, dark = true) {
        PasswordsScreen(passwordItems, onSave = { _, _ -> PasswordResult.Connected }, onSkip = {}, onDone = {})
    }

    @Test fun filesNoticePhoneLight() = shoot("files_passwords_notice_phone_light", PHONE, dark = false) {
        dev.hfps.hoard.app.ui.files.FilesScreen(Samples.files, onOpenSettings = {}, passwordsNeeded = 2)
    }
}
