package dev.hfps.hoard.app.cull

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.data.CullSettings
import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The culling folder's behaviour (useCullFolder + useCullAssist): open, decisions saved and written as sidecars, undo,
 * the ADR 0011 import at open and on resume, the read-only folder, analyses from the cache and natively, suggestions
 * that never decide, delete rejects. Room in memory, a fake native side.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CullViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: HoardDatabase
    private val key = CullKey("m1", "photos", "shoot")
    private val settings = MutableStateFlow(CullSettings())

    @Before fun setUp() {
        db = HoardDatabase.open(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After fun tearDown() = db.close()

    private fun idle(times: Int = 30) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun vm(backend: FakeCullBackend): CullViewModel {
        val store = androidx.lifecycle.ViewModelStore().also { stores += it }
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CullViewModel(key, backend, db.cull(), settings, compute = Dispatchers.Unconfined, now = { backend.clock + 500 }) as T
        }
        return androidx.lifecycle.ViewModelProvider(store, factory)[CullViewModel::class.java].also { idle(60) }
    }

    @Test
    fun `opens the folder in capture order with bursts and analyses, and caches them`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        val view = vm.view.value
        assertThat(view.core.status).isEqualTo(CullStatus.Ready)
        assertThat(view.core.shots).hasSize(24)
        assertThat(view.core.metadataDone).isTrue()
        assertThat(view.members.values.map { it.size }).containsExactly(4, 4, 4)
        assertThat(view.items).hasSize(24 - 9)
        assertThat(view.analysis.done).isEqualTo(24)
        assertThat(db.cull().analyses("m1", "photos", "shoot")).hasSize(24)
        assertThat(db.cull().session("m1", "photos", "shoot")?.shotCount).isEqualTo(24)
        vm.close()
    }

    @Test
    fun `a decision is saved, written as a sidecar and undone as one step`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        vm.decide(listOf("dsc00001")) { d, _ -> d.withFlag(Flag.Pick).withRating(3) }
        idle()
        assertThat(db.cull().forFolder("m1", "photos", "shoot").single().flag).isEqualTo("pick")
        assertThat(backend.sidecars["dsc00001"]?.first).isEqualTo(XmpFields(3, XmpFlag.PICK))
        val row = db.cull().sidecars("m1", "photos", "shoot").single()
        assertThat(row.synced).isEqualTo(1)
        assertThat(vm.view.value.core.undo).hasSize(1)
        vm.undo()
        idle()
        assertThat(vm.view.value.core.decisions).isEmpty()
        assertThat(db.cull().forFolder("m1", "photos", "shoot")).isEmpty()
        assertThat(backend.sidecars["dsc00001"]?.first).isEqualTo(XmpFields(0, XmpFlag.NONE))
        vm.close()
    }

    @Test
    fun `a sidecar newer than Hoard's decision wins at open, an older one does not`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        // dsc00001: decided in Hoard at t=5000 (not written); its sidecar is older → Hoard wins.
        db.cull().saveDecisions("m1", "photos", "shoot", listOf(Triple("dsc00001", "reject", 0)))
        db.cull().saveSidecars("m1", "photos", "shoot", listOf(dev.hfps.hoard.data.CullDao.SidecarUpdate("dsc00001", decidedAt = 5_000, setDecidedAt = true, synced = false)))
        backend.sidecars["dsc00001"] = XmpFields(5, XmpFlag.PICK) to 4_000L
        // dsc00002: never decided in Hoard → any sidecar wins (Lightroom's rating comes in).
        backend.sidecars["dsc00002"] = XmpFields(4, XmpFlag.NONE) to 3_000L
        val vm = vm(backend)
        val decisions = vm.view.value.core.decisions
        assertThat(decisions["dsc00001"]).isEqualTo(Decision(Flag.Reject, 0))
        assertThat(decisions["dsc00002"]).isEqualTo(Decision(null, 4))
        val imported = db.cull().forFolder("m1", "photos", "shoot").first { it.shotId == "dsc00002" }
        assertThat(imported.rating).isEqualTo(4)
        // ADR 0016 rule 4: the imported decision is dated by the sidecar's mtime, not by the import.
        assertThat(java.time.Instant.parse(imported.updatedAt).toEpochMilli()).isEqualTo(3_000L)
        // A sidecar changed in Reveal while Hoard was in the background is read on resume.
        backend.sidecars["dsc00003"] = XmpFields(0, XmpFlag.REJECT) to 9_000L
        vm.onResume()
        idle()
        assertThat(vm.view.value.core.decisions["dsc00003"]?.flag).isEqualTo(Flag.Reject)
        assertThat(java.time.Instant.parse(db.cull().forFolder("m1", "photos", "shoot").first { it.shotId == "dsc00003" }.updatedAt).toEpochMilli()).isEqualTo(9_000L)
        vm.close()
    }

    @Test
    fun `a read-only folder keeps decisions in Hoard and never writes`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder(), access = SmbAccess.READ)
        val vm = vm(backend)
        assertThat(vm.view.value.sidecarsReadOnly).isTrue()
        vm.decide(listOf("dsc00002")) { d, _ -> d.withFlag(Flag.Pick) }
        idle()
        assertThat(backend.written).isEmpty()
        assertThat(db.cull().forFolder("m1", "photos", "shoot")).hasSize(1)
        // With "Save ratings as XMP" off the note is not shown either.
        settings.value = CullSettings(saveXmp = false)
        idle()
        assertThat(vm.view.value.sidecarsReadOnly).isFalse()
        vm.close()
    }

    @Test
    fun `sidecar writes stop when Save ratings as XMP is off`() = runBlocking {
        settings.value = CullSettings(saveXmp = false)
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        vm.decide(listOf("dsc00004")) { d, _ -> d.withFlag(Flag.Reject) }
        idle()
        assertThat(backend.written).isEmpty()
        vm.close()
    }

    @Test
    fun `cached analyses are reused and stale ones measured again`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val shot = backend.shots.first()
        val raw = shot.raw!!
        db.cull().putAnalyses(
            listOf(
                CullAnalysisEntity("m1", "photos", raw.path, "shoot", raw.size, raw.modifiedAt, ANALYSIS_VERSION, AnalysisJson.write(FakeCullBackend.cleanAnalysis(blur = 0.9)), "2026-11-14T00:00:00.000Z"),
                CullAnalysisEntity("m1", "photos", backend.shots[1].raw!!.path, "shoot", 1, null, ANALYSIS_VERSION, AnalysisJson.write(FakeCullBackend.cleanAnalysis(blur = 0.9)), "2026-11-14T00:00:00.000Z"),
            ),
        )
        val vm = vm(backend)
        val view = vm.view.value
        // The first row matches the file: its blur (0.9) is used. The second is stale (size): measured again (0.35).
        assertThat(view.flags[shot.id]).containsExactly(ShotFlag.Blurry)
        assertThat(view.flags[backend.shots[1].id]).isEmpty()
        vm.close()
    }

    @Test
    fun `suggestions never decide, accepting them is one undo step`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        backend.analyses["dsc00006"] = FakeCullBackend.cleanAnalysis(subject = 400.0)
        backend.analyses["dsc00010"] = FakeCullBackend.cleanAnalysis(blur = 0.8)
        val vm = vm(backend)
        vm.setSuggesting(true)
        idle()
        val suggestions = vm.view.value.suggestions
        assertThat(suggestions["dsc00006"]?.action).isEqualTo(SuggestionAction.Keep)
        assertThat(suggestions["dsc00010"]?.action).isEqualTo(SuggestionAction.Reject)
        assertThat(vm.view.value.core.decisions).isEmpty()
        val count = vm.accept(suggestions.keys.toList())
        idle()
        assertThat(count).isEqualTo(suggestions.size)
        assertThat(vm.view.value.core.decisions["dsc00006"]?.flag).isEqualTo(Flag.Pick)
        vm.undo()
        idle()
        assertThat(vm.view.value.core.decisions).isEmpty()
        vm.close()
    }

    @Test
    fun `x on a burst and keep this one go through decide as single undo steps`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        val burst = vm.view.value.members.values.first()
        vm.apply(rejectBurst(vm.view.value.core.decisions, burst, RejectMode.Toggle))
        idle()
        assertThat(burst.map { vm.view.value.core.decisions[it]?.flag }.toSet()).containsExactly(Flag.Reject)
        vm.undo()
        vm.apply(keepThisOne(vm.view.value.core.decisions, burst, burst[2]))
        idle()
        assertThat(burstStatus(burst, vm.view.value.core.decisions)).isEqualTo(BurstStatus(4, 1, 4, true))
        assertThat(backend.sidecars.filterKeys { it in burst }.values.map { it.first.flag }).containsExactly(XmpFlag.PICK, XmpFlag.REJECT, XmpFlag.REJECT, XmpFlag.REJECT)
        vm.close()
    }

    @Test
    fun `filters and stacks follow the controls`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        vm.decide(listOf("dsc00001", "dsc00002")) { d, _ -> d.withFlag(Flag.Pick) }
        vm.setFilter(CullFilter(FlagFilter.Picked))
        idle()
        assertThat(vm.view.value.visible.map { it.id }).containsExactly("dsc00001", "dsc00002")
        assertThat(vm.view.value.collapse).isFalse()
        vm.setFilter(CullFilter())
        vm.setStack(false)
        idle()
        assertThat(vm.view.value.items).hasSize(24)
        vm.setStack(true)
        vm.toggleBurst(vm.view.value.members.keys.first())
        idle()
        assertThat(vm.view.value.items).hasSize(24 - 6)
        vm.close()
    }

    @Test
    fun `removed shots leave the list and the database`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        vm.decide(listOf("dsc00003")) { d, _ -> d.withFlag(Flag.Reject) }
        idle()
        vm.removeShots(listOf("dsc00003"))
        idle()
        assertThat(vm.view.value.core.shots).hasSize(23)
        assertThat(db.cull().forFolder("m1", "photos", "shoot")).isEmpty()
        assertThat(db.cull().session("m1", "photos", "shoot")?.shotCount).isEqualTo(23)
        vm.close()
    }

    @Test
    fun `the last shot is remembered for Continue`() = runBlocking {
        val backend = FakeCullBackend(tmp.newFolder())
        val vm = vm(backend)
        vm.setLastShot("dsc00007")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        idle()
        assertThat(db.cull().session("m1", "photos", "shoot")?.lastShotId).isEqualTo("dsc00007")
        vm.close()
    }

    private val stores = ArrayList<androidx.lifecycle.ViewModelStore>()

    /** Clears the view model as leaving the screen would (its store is cleared). */
    private fun CullViewModel.close() {
        stores.forEach { it.clear() }
        stores.clear()
    }

    @Suppress("unused")
    private fun legacy(id: String) = CullDecisionEntity("m1", "photos", "shoot", id, "pick", 0, "2026-11-01T00:00:00.000Z")
}
