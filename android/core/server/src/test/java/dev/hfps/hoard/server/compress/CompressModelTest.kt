package dev.hfps.hoard.server.compress

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressEstimate
import dev.hfps.hoard.server.api.CompressEstimateItem
import dev.hfps.hoard.server.api.CompressEstimateRequest
import dev.hfps.hoard.server.api.CompressInfo
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.CompressJobCreate
import dev.hfps.hoard.server.api.CompressJobPatch
import dev.hfps.hoard.server.api.CompressProgress
import dev.hfps.hoard.server.api.CompressProfile
import dev.hfps.hoard.server.api.CompressSelect
import dev.hfps.hoard.server.api.CompressTarget
import dev.hfps.hoard.server.api.CompressTargets
import dev.hfps.hoard.server.api.CompressTotals
import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * ADR 0025, phase A1: the features/targets gate, the estimate sheet's model, Settings › Compress videos' split, and the
 * `/v1/compress/…` calls against a fake hoard-server.
 */
class CompressModelTest {
    private val ok = CompressTarget(ok = true)
    private fun info(nas: CompressTarget = ok, backup: CompressTarget = ok, offerAv1: Boolean = false, phone: CompressTarget? = ok) = CompressInfo(
        available = true,
        targets = CompressTargets(nas = nas, phone = phone, backup = backup),
        nasWrite = nas.ok,
        offerAv1 = offerAv1,
        profiles = listOf(CompressProfile(Compress.PROFILE_SAME, default = true), CompressProfile(Compress.PROFILE_SMALLER), CompressProfile(Compress.PROFILE_AV1, optIn = true)),
    )

    @Test
    fun withoutTheFeatureEverythingIsHidden() {
        assertThat(CompressGate.offer(listOf("items", "trash"), info(), Compress.TARGET_NAS)).isEqualTo(CompressOffer.Hidden)
        assertThat(CompressGate.offer(listOf(Features.COMPRESS), null, Compress.TARGET_NAS)).isEqualTo(CompressOffer.Hidden)
        assertThat(CompressGate.settingsVisible(listOf("items"), null)).isFalse()
        // A server that still reports compression without the feature (the GPU went away): Settings stays for restore.
        assertThat(CompressGate.settingsVisible(listOf("items"), info().copy(available = false))).isTrue()
    }

    @Test
    fun aTargetIsOfferedOnlyWhenItIsOk() {
        val f = listOf(Features.COMPRESS)
        assertThat(CompressGate.offer(f, info(), Compress.TARGET_NAS)).isEqualTo(CompressOffer.Ready)
        assertThat(CompressGate.offer(f, info(nas = CompressTarget(false, "no_write_mount")), Compress.TARGET_NAS))
            .isEqualTo(CompressOffer.Unavailable("no_write_mount"))
        // Phone and backup are judged apart: no write mount still offers backup jobs.
        assertThat(CompressGate.offer(f, info(nas = CompressTarget(false, "no_write_mount")), Compress.TARGET_BACKUP)).isEqualTo(CompressOffer.Ready)
        assertThat(CompressGate.offer(f, info(backup = CompressTarget(false, "no_backup_root")), Compress.TARGET_BACKUP))
            .isEqualTo(CompressOffer.Unavailable("no_backup_root"))
        // The phone's own replace (Hoard 2.8.0): only with `targets.phone.ok`, and only on a phone that can write in place.
        assertThat(CompressGate.offer(f, info(), Compress.TARGET_PHONE)).isEqualTo(CompressOffer.Ready)
        assertThat(CompressGate.offer(f, info(phone = CompressTarget(false, "no_backup_root")), Compress.TARGET_PHONE))
            .isEqualTo(CompressOffer.Unavailable("no_backup_root"))
        assertThat(CompressGate.offer(f, info(phone = null), Compress.TARGET_PHONE)).isEqualTo(CompressOffer.Hidden)
        assertThat(CompressGate.offer(f, info(), Compress.TARGET_PHONE, PhoneAbility.OldAndroid)).isEqualTo(CompressOffer.Unavailable(CompressGate.REASON_PHONE_ANDROID11))
        assertThat(CompressGate.offer(f, info(), Compress.TARGET_PHONE, PhoneAbility.NoAllFiles)).isEqualTo(CompressOffer.Unavailable(CompressGate.REASON_PHONE_ALL_FILES))
        // A phone that cannot write still shows the server's reason first.
        assertThat(CompressGate.offer(f, info(phone = CompressTarget(false, "no_gpu")), Compress.TARGET_PHONE, PhoneAbility.NoAllFiles)).isEqualTo(CompressOffer.Unavailable("no_gpu"))
        // A target without a reason falls back to the object's reason.
        assertThat(CompressGate.offer(f, info(nas = CompressTarget(false)).copy(reason = "no_gpu"), Compress.TARGET_NAS)).isEqualTo(CompressOffer.Unavailable("no_gpu"))
    }

    @Test
    fun av1IsOfferedOnlyOnceTheOwnerTurnedItOnAndNeverForAutomaticFolders() {
        assertThat(CompressGate.profiles(info())).containsExactly(Compress.PROFILE_SAME, Compress.PROFILE_SMALLER).inOrder()
        assertThat(CompressGate.profiles(info(offerAv1 = true))).containsExactly(Compress.PROFILE_SAME, Compress.PROFILE_SMALLER, Compress.PROFILE_AV1).inOrder()
        assertThat(CompressGate.profiles(info(offerAv1 = true), auto = true)).doesNotContain(Compress.PROFILE_AV1)
    }

    private fun page(items: List<CompressEstimateItem>, totals: CompressTotals, pending: Int = 0, next: String? = null) = CompressEstimate(items, totals, pending, next)

    @Test
    fun theEstimateSummaryUsesTheSelectionsTotalsAndEveryPagesWarnings() {
        val totals = CompressTotals(items = 312, encode = 129, skip = mapOf("older_compression" to 180, "efficient" to 2, "unreadable" to 1), size = 29_100_000_000, estSize = 5_600_000_000, estMs = 2_100_000)
        val pages = listOf(
            page(listOf(CompressEstimateItem("a", decision = "encode", warnings = listOf("per_frame_metadata"))), totals, next = "1"),
            page(listOf(CompressEstimateItem("b", decision = "encode", warnings = listOf("hdr10plus")), CompressEstimateItem("c", decision = "skip", reason = "efficient", warnings = listOf("pcm_audio"))), totals),
        )
        val s = EstimateSummary.of(pages)
        assertThat(s.videos).isEqualTo(312)
        assertThat(s.encode).isEqualTo(129)
        assertThat(s.saving).isEqualTo(23_500_000_000)
        assertThat(s.skipped).containsExactly("older_compression" to 180, "efficient" to 2, "unreadable" to 1).inOrder()
        assertThat(s.skippedTotal).isEqualTo(183)
        assertThat(s.hdr10Plus).isTrue()
        assertThat(s.metaSaved).isTrue()
        // A skipped video's warning is not the job's.
        assertThat(s.warnings).doesNotContain("pcm_audio")
        assertThat(EstimateSummary.of(emptyList()).videos).isEqualTo(0)
    }

    @Test
    fun theSheetStartsOnlyWithSomethingToEncode() {
        var st = CompressSheetState(Compress.TARGET_NAS, listOf(Compress.PROFILE_SAME, Compress.PROFILE_SMALLER))
        assertThat(st.profile).isEqualTo(Compress.PROFILE_SAME)
        assertThat(st.canStart).isFalse()
        val none = EstimateSummary(3, 0, 0, 0, 0, listOf("older_compression" to 3), emptySet(), 0)
        assertThat(st.withEstimate(none).canStart).isFalse()
        val some = EstimateSummary(3, 2, 100, 20, 1000, emptyList(), emptySet(), 0)
        st = st.withEstimate(some)
        assertThat(st.canStart).isTrue()
        assertThat(st.loading).isFalse()
        // Videos still being read: the job estimates again, Start is allowed; the sheet keeps loading.
        val pending = EstimateSummary(3, 0, 0, 0, 0, emptyList(), emptySet(), 3)
        assertThat(st.withEstimate(pending).canStart).isTrue()
        assertThat(st.withEstimate(pending).loading).isTrue()
        // Another profile: a new estimate.
        val smaller = st.withProfile(Compress.PROFILE_SMALLER)
        assertThat(smaller.estimate).isNull()
        assertThat(smaller.canStart).isFalse()
        assertThat(st.withProfile(Compress.PROFILE_AV1)).isEqualTo(st)
        // An error or a start in flight: no Start.
        assertThat(st.failed("no_write_mount").canStart).isFalse()
        assertThat(st.copy(starting = true).canStart).isFalse()
        // An automatic switch needs no video now (it covers new ones).
        val auto = CompressSheetState(Compress.TARGET_NAS, listOf(Compress.PROFILE_SAME), auto = true, loading = false)
        assertThat(auto.withEstimate(none).canStart).isTrue()
    }

    private fun job(id: String, auto: Boolean = false, state: String = CompressJob.STATE_ACTIVE, toDo: Int = 0, paused: Boolean = false) =
        CompressJob(id, auto = auto, state = state, paused = paused, progress = CompressProgress(done = 1, toDo = toDo))

    @Test
    fun noSpaceIsAWaitNotAFailure() {
        // ADR 0025 Amendment (2.9.1): items waiting for room (0.14.0 `space`) and an older server's failed no_space.
        val jobs = listOf(
            job("run", toDo = 3).copy(failed = mapOf("no_space" to 2, "encoder" to 1)),
            job("other", toDo = 1),
            job("work", toDo = 1),
        )
        val waiting = listOf(
            dev.hfps.hoard.server.api.CompressItem(id = "1", jobId = "run", state = "ready", reason = "no_space", space = dev.hfps.hoard.server.api.CompressItemSpace("nas", 900, 5_000)),
            dev.hfps.hoard.server.api.CompressItem(id = "2", jobId = "run", state = "ready", reason = "no_space", space = dev.hfps.hoard.server.api.CompressItemSpace("nas", 900, 4_000)),
            dev.hfps.hoard.server.api.CompressItem(id = "3", jobId = "work", state = "queued", reason = "no_space", space = dev.hfps.hoard.server.api.CompressItemSpace("work")),
            // Not waiting: another reason, or settled.
            dev.hfps.hoard.server.api.CompressItem(id = "4", jobId = "other", state = "ready", reason = ""),
            dev.hfps.hoard.server.api.CompressItem(id = "5", jobId = "other", state = "failed", reason = "no_space"),
        )
        val o = CompressOverview.of(jobs, waiting)
        assertThat(o.spaceWaits["run"]).isEqualTo(SpaceWait(4, SpaceWait.WHERE_NAS, 4_000))
        assertThat(o.spaceWaits["work"]).isEqualTo(SpaceWait(1, SpaceWait.WHERE_WORK, null))
        assertThat(o.spaceWaits).doesNotContainKey("other")
        assertThat(o.spaceWait).isEqualTo(SpaceWait(5, SpaceWait.WHERE_NAS, 4_000))
        // no_space is no longer among the failures.
        assertThat(SpaceWait.failures(jobs[0])).containsExactly("encoder", 1)
        assertThat(CompressOverview.of(listOf(job("x", toDo = 1))).spaceWait).isNull()
        // 0.14.0's item answer parses its `space`.
        val item = ServerJson.decodeFromString(dev.hfps.hoard.server.api.CompressItem.serializer(), """{"id":"9","state":"ready","reason":"no_space","space":{"where":"nas","needed":10,"free":3}}""")
        assertThat(item.space).isEqualTo(dev.hfps.hoard.server.api.CompressItemSpace("nas", 10, 3))
    }

    @Test
    fun settingsSplitsJobsIntoRunningAutomaticAndHistory() {
        val o = CompressOverview.of(
            listOf(
                job("run", toDo = 3),
                job("paused", toDo = 2, paused = true, state = CompressJob.STATE_PAUSED),
                job("auto", auto = true),
                job("auto-off", auto = true, state = CompressJob.STATE_CANCELLED),
                job("done", state = CompressJob.STATE_DONE),
                job("cancelled", state = CompressJob.STATE_CANCELLED, toDo = 1),
            ),
        )
        assertThat(o.running.map { it.id }).containsExactly("run", "paused").inOrder()
        assertThat(o.automatic.map { it.id }).containsExactly("auto")
        assertThat(o.history.map { it.id }).containsExactly("done", "cancelled").inOrder()
    }

    @Test
    fun itemsReadTheCompressionFields() {
        val output = ServerJson.decodeFromString(
            Item.serializer(),
            """{"id":"o","rev":3,"kind":"video","compressed":{"from":"v1:a","fromItem":"i","profile":"hevc-cq28","q":26,"at":5,"original":"trashed","originalSize":463000000,"metaSidecar":true},"unknown":1}""",
        )
        assertThat(output.compressed!!.originalInTrash).isTrue()
        assertThat(output.compressed!!.q).isEqualTo(26)
        val original = ServerJson.decodeFromString(
            Item.serializer(),
            """{"id":"i","rev":4,"replacedBy":"o","compressedTo":"o","trashed":{"since":7},"files":[{"fingerprint":"v1:a","role":"video","size":9,"locations":[{"source":"nas","folderId":"f","path":"a.mp4","trash":".hoard-trash/2026-11-19/a.mp4","held":true}]}]}""",
        )
        assertThat(original.compressedTo).isEqualTo("o")
        assertThat(original.files.single().locations.single().held).isTrue()
        // Old servers: nothing.
        val plain = ServerJson.decodeFromString(Item.serializer(), """{"id":"p","rev":1}""")
        assertThat(plain.compressed).isNull()
        assertThat(plain.compressedTo).isNull()
    }

    // --- The routes, against a fake hoard-server ---

    @Test
    fun theRoutesSpeakTheServersContract() {
        FakeServer().use { server ->
            server.version = "0.13.0"
            server.features = listOf("items", Features.COMPRESS)
            server.compress = """{"available":true,"reason":null,"targets":{"nas":{"ok":false,"reason":"no_write_mount"},"phone":{"ok":true},"backup":{"ok":true}},
                "attention":{"stalled":1,"stalledSince":5,"untrackedTrash":0},"gpu":"NVIDIA GeForce RTX 4080","codecs":["hevc","av1"],"nasWrite":false,"offerAv1":false,"trashDays":30,
                "profiles":[{"id":"hevc-cq28","codec":"hevc","q":28,"default":true}],"limits":{"maxItemsPerJob":5000,"maxEstimate":500,"maxBatch":1000}}"""
            val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
            var estimates = 0
            server.extra = { ex, path, query, raw ->
                val body = raw.toString(Charsets.UTF_8)
                bodies += "${ex.requestMethod} $path ${query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }} $body".trim()
                when {
                    path == "/v1/compress/estimate" -> {
                        estimates++
                        if (estimates == 1) ex.responseHeaders.add("Retry-After", "2")
                        server.send(ex, 200, """{"items":[{"itemId":"i1","decision":"${if (estimates == 1) "pending" else "encode"}","size":10}],"totals":{"items":1,"encode":${if (estimates == 1) 0 else 1},"skip":{},"size":10,"estSize":2,"estMs":3},"pending":${if (estimates == 1) 1 else 0},"next":null}""")
                        true
                    }
                    path == "/v1/compress/jobs" && ex.requestMethod == "POST" && body.contains("\"auto\":true") -> {
                        server.send(ex, 409, """{"error":{"code":"conflict","message":"exists","details":{"reason":"exists","job":{"id":"j0"}}}}""")
                        true
                    }
                    path == "/v1/compress/jobs" && ex.requestMethod == "POST" -> {
                        server.send(ex, 201, """{"id":"j1","target":"backup","profile":"hevc-cq28","auto":false,"priority":2,"state":"active","createdAt":1,"counts":{"queued":1},"progress":{"done":0,"toDo":1},"queued":1,"busy":[]}""")
                        true
                    }
                    path == "/v1/compress/jobs" -> {
                        server.send(ex, 200, """{"jobs":[{"id":"j1","target":"nas","auto":true,"state":"active","createdAt":1,"select":{"folders":[{"folderId":"f","path":"2024","recursive":true}]},"bytes":{"saved":5,"inTrash":7},"progress":{"done":1,"toDo":0}}],"cursor":null,"hasMore":false}""")
                        true
                    }
                    path == "/v1/compress/jobs/j1" -> {
                        server.send(ex, 200, """{"id":"j1","auto":false,"state":"cancelled","createdAt":1}""")
                        true
                    }
                    path == "/v1/compress/purge" || path == "/v1/compress/restore" || path == "/v1/compress/cancel" -> {
                        server.send(ex, 200, """{"items":{"i1":{"id":"12","state":"purged","alsoKeptBy":["claim"]},"i2":{"error":"state"}}}""")
                        true
                    }
                    path == "/v1/compress/lookup" -> {
                        server.send(ex, 200, """{"fingerprints":{"v1:a":{"role":"original","id":"12","itemId":"i1","state":"replaced","jobId":"j1","profile":"hevc-cq28","purgeAfter":9}},"missing":["v1:b"]}""")
                        true
                    }
                    path == "/v1/compress/items" -> {
                        server.send(ex, 200, """{"items":[{"id":"12","jobId":"j1","itemId":"i1","fingerprint":"v1:a","state":"encoding","reason":"","size":10,"progress":0.5}],"cursor":null,"hasMore":false}""")
                        true
                    }
                    else -> false
                }
            }
            val api = ServerApi(server.url, { server.key })
            val info = api.server()
            assertThat(info.compress!!.targets!!.nas!!.reason).isEqualTo("no_write_mount")
            assertThat(info.compress!!.attention!!.stalled).isEqualTo(1)
            assertThat(CompressGate.offer(info.features, info.compress, Compress.TARGET_NAS)).isEqualTo(CompressOffer.Unavailable("no_write_mount"))
            assertThat(CompressGate.offer(info.features, info.compress, Compress.TARGET_BACKUP)).isEqualTo(CompressOffer.Ready)

            val first = api.compressEstimate(CompressEstimateRequest(Compress.TARGET_BACKUP, CompressSelect(itemIds = listOf("i1")), Compress.PROFILE_SAME))
            assertThat(first.pending).isEqualTo(1)
            assertThat(first.retryAfterSeconds).isEqualTo(2)
            val second = api.compressEstimate(CompressEstimateRequest(Compress.TARGET_BACKUP, CompressSelect(itemIds = listOf("i1")), Compress.PROFILE_SAME))
            assertThat(second.retryAfterSeconds).isNull()
            assertThat(EstimateSummary.of(listOf(second)).encode).isEqualTo(1)

            val job = api.createCompressJob(CompressJobCreate(Compress.TARGET_BACKUP, CompressSelect(itemIds = listOf("i1")), Compress.PROFILE_SAME, "req-1"))
            assertThat(job.queued).isEqualTo(1)
            val conflict = assertThrows(ServerException::class.java) {
                api.createCompressJob(CompressJobCreate(Compress.TARGET_NAS, CompressSelect(folders = listOf(dev.hfps.hoard.server.api.CompressFolderSelect("f", "2024"))), Compress.PROFILE_SAME, "req-2", auto = true))
            }
            assertThat(conflict.status).isEqualTo(409)
            assertThat(conflict.reason).isEqualTo("exists")

            val overview = CompressOverview.of(api.compressJobs(auto = true).jobs)
            assertThat(overview.automatic.single().bytes.inTrash).isEqualTo(7)
            assertThat(api.patchCompressJob("j1", CompressJobPatch(auto = false)).state).isEqualTo(CompressJob.STATE_CANCELLED)

            val purge = api.compressPurge(listOf("i1", "i2"))
            assertThat(purge.items.getValue("i1").alsoKeptBy).containsExactly("claim")
            assertThat(purge.items.getValue("i2").error).isEqualTo("state")
            assertThat(api.compressRestore(listOf("i1")).items.getValue("i1").state).isEqualTo("purged")
            val lookup = api.compressLookup(listOf("v1:a", "v1:b"))
            assertThat(lookup.fingerprints.getValue("v1:a").purgeAfter).isEqualTo(9)
            assertThat(lookup.missing).containsExactly("v1:b")
            assertThat(api.compressItems(states = "queued,encoding").items.single().progress).isEqualTo(0.5)

            // What went on the wire: nulls left out, the selection as the server reads it, PATCH with only `auto`.
            assertThat(bodies.first { it.startsWith("POST /v1/compress/estimate") }).contains("""{"target":"backup","select":{"itemIds":["i1"]},"profile":"hevc-cq28"}""")
            assertThat(bodies.first { it.contains("req-1") }).contains(""""requestId":"req-1"""")
            assertThat(bodies.first { it.contains("req-1") }).doesNotContain("auto")
            assertThat(bodies.first { it.startsWith("PATCH") }).endsWith("""{"auto":false}""")
            assertThat(bodies.first { it.startsWith("GET /v1/compress/jobs") }).contains("auto=true")
            assertThat(bodies.first { it.startsWith("POST /v1/compress/purge") }).endsWith("""{"itemIds":["i1","i2"]}""")
            assertThat(bodies.first { it.startsWith("GET /v1/compress/items") }).contains("state=queued,encoding")
        }
    }

    @Test
    fun theSubfoldersCountIsReadFromTheTotalsAndIsZeroFromAnOlderServer() {
        val newer = ServerJson.decodeFromString(CompressEstimate.serializer(), """{"items":[],"totals":{"items":5,"encode":5,"subfolders":1},"pending":0}""")
        assertThat(EstimateSummary.of(listOf(newer)).subfolders).isEqualTo(1)
        val older = ServerJson.decodeFromString(CompressEstimate.serializer(), """{"items":[],"totals":{"items":5,"encode":5},"pending":0}""")
        assertThat(EstimateSummary.of(listOf(older)).subfolders).isEqualTo(0)
        // Beside `pending` instead, and never more than the videos.
        val beside = ServerJson.decodeFromString(CompressEstimate.serializer(), """{"items":[],"totals":{"items":5},"pending":0,"subfolders":9}""")
        assertThat(EstimateSummary.of(listOf(beside)).subfolders).isEqualTo(5)
    }

    /** Drift guard: every reason constant of the server's compression package is one the app has a sentence for. */
    @Test
    fun everyReasonOfTheServerIsListed() {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "server/internal/compress") }.firstOrNull { it.isDirectory }
        org.junit.Assume.assumeTrue("server sources not next to the app", dir != null)
        val estimate = java.io.File(dir, "estimate.go").readText()
        val consts = Regex("""Reason\w+\s*=\s*"([a-z_]+)"""").findAll(estimate).map { it.groupValues[1] }.toSet()
        assertThat(consts).isNotEmpty()
        val known = (CompressReasons.SKIP + CompressReasons.FAILED + CompressReasons.ROUTE).toSet()
        assertThat(consts.filter { it !in known }).isEmpty()
        val gpu = java.io.File(dir, "gpu.go").readText()
        val targets = Regex("""st\.Reason(?:, st\.Detail)? = "([a-z_]+)"""").findAll(gpu).map { it.groupValues[1] }.toSet()
        assertThat(targets).isNotEmpty()
        assertThat(targets.filter { it !in CompressReasons.TARGET }).isEmpty()
        val gate = java.io.File(dir, "..").listFiles().orEmpty().filter { it.isDirectory }.flatMap { d -> d.listFiles().orEmpty().filter { it.extension == "go" && !it.name.endsWith("_test.go") } }
            .joinToString("\n") { it.readText() }
        for (r in listOf("no_write_mount", "write_mount_mismatch", "write_mount_read_only", "unstable_inode", "no_backup_root")) {
            if (r in gate) assertThat(CompressReasons.TARGET).contains(r)
        }
    }
}
