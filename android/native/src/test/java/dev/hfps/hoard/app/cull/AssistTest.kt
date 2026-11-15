package dev.hfps.hoard.app.cull

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigInteger

/** Port of src/features/cull/assist.test.ts. */
class AssistTest {
    private fun shot(stem: String) = Shot(
        id = stem.lowercase(),
        stem = stem,
        raw = ShotFile("$stem.ARW", "DCIM/$stem.ARW", 25_000_000, null, 0),
        rendered = ShotFile("$stem.JPG", "DCIM/$stem.JPG", 6_000_000, null, 0),
        others = emptyList(),
    )

    private val hash = "a161f1e1e3e36025"
    private val colors = "80".repeat(48)

    /** Flip n low bits of the base hash. */
    private fun hashWith(n: Int): String {
        val value = BigInteger(hash, 16).xor(BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE))
        return value.toString(16).padStart(16, '0')
    }

    private fun face(eyes: Double?, prominent: Boolean = true) = Face(0.4, 0.3, 0.15, 0.2, eyes, eyes, null, prominent, 100.0, 0.3)

    private fun analysis(
        subject: Double = 200.0,
        blur: Double = 0.35,
        faces: List<Face> = emptyList(),
        mean: Double = 0.45,
        highlights: Double = 0.01,
        blownArea: Double = 0.0,
        faceHighlights: Double = 0.0,
        shadows: Double = 0.001,
        hash: String = this.hash,
        colors: String = this.colors,
    ) = Analysis(
        version = 2,
        source = "rawPreview",
        width = 1536,
        height = 1024,
        millis = 50,
        sharpness = Sharpness(50.0, List(16) { 10.0 }, 5, subject, if (faces.isNotEmpty()) "face" else "tile", blur),
        facesDetected = true,
        faces = faces,
        exposure = ExposureStats(List(64) { 1.0 / 64 }, mean, highlights, shadows, blownArea, faceHighlights),
        hash = hash,
        colors = colors,
    )

    private fun meta(vararg times: Pair<String, Long>) = times.associate { (id, t) -> id to ShotMetadata(captureMillis = t) }

    private val default = AssistSettings.DEFAULT

    @Test
    fun `flags blur by sensitivity, eyes on prominent faces only, exposure`() {
        val soft = analysis(blur = 0.62)
        assertThat(flagsOf(soft, default)).containsExactly(ShotFlag.Blurry)
        assertThat(flagsOf(soft, default.copy(blur = Sensitivity.Low))).isEmpty()
        assertThat(flagsOf(analysis(blur = 0.55), default.copy(blur = Sensitivity.High))).containsExactly(ShotFlag.Blurry)
        assertThat(flagsOf(analysis(faces = listOf(face(0.1))), default)).containsExactly(ShotFlag.EyesClosed)
        assertThat(flagsOf(analysis(faces = listOf(face(0.95), face(0.05, false))), default)).isEmpty()
        assertThat(flagsOf(analysis(faces = listOf(face(0.05).copy(rightEyeOpen = null))), default)).isEmpty()
        assertThat(flagsOf(analysis(faces = listOf(face(0.4))), default)).isEmpty()
        assertThat(flagsOf(analysis(faces = listOf(face(0.4))), default.copy(eyes = Sensitivity.High))).containsExactly(ShotFlag.EyesClosed)
        assertThat(flagsOf(analysis(highlights = 0.12, blownArea = 0.11), default)).containsExactly(ShotFlag.Overexposed)
        assertThat(flagsOf(analysis(mean = 0.72), default)).containsExactly(ShotFlag.Overexposed)
        assertThat(flagsOf(analysis(shadows = 0.09), default)).containsExactly(ShotFlag.Underexposed)
        assertThat(flagsOf(analysis(mean = 0.18), default)).containsExactly(ShotFlag.Underexposed)
        assertThat(sharpnessScore(analysis(blur = 0.35))).isEqualTo(65)
    }

    @Test
    fun `over-exposure counts large blown regions and blown faces, not light sources`() {
        assertThat(flagsOf(analysis(highlights = 0.105, blownArea = 0.012), default)).isEmpty()
        assertThat(exposureOf(analysis(highlights = 0.18, blownArea = 0.02).exposure).verdict).isEqualTo(ExposureVerdict.Ok)
        assertThat(exposureOf(analysis(highlights = 0.12, blownArea = 0.1).exposure)).isEqualTo(ExposureOutcome(ExposureVerdict.Over, ExposureReason.BlownArea))
        assertThat(exposureOf(analysis(highlights = 0.076, blownArea = 0.066).exposure).verdict).isEqualTo(ExposureVerdict.Ok)
        assertThat(exposureOf(analysis(highlights = 0.02, faceHighlights = 0.2, faces = listOf(face(0.9))).exposure))
            .isEqualTo(ExposureOutcome(ExposureVerdict.Over, ExposureReason.Face))
        assertThat(exposureOf(analysis(mean = 0.71).exposure).reason).isEqualTo(ExposureReason.Mean)
        assertThat(exposureOf(analysis(shadows = 0.08).exposure)).isEqualTo(ExposureOutcome(ExposureVerdict.Under, ExposureReason.Shadows))
    }

    @Test
    fun `one eyes reading for the flag, the chips and the details`() {
        val three = analysis(faces = listOf(face(0.95), face(0.1), face(0.9)))
        val summary = eyesSummary(three, Sensitivity.Medium)
        assertThat(summary.prominent).hasSize(3)
        assertThat(summary.states).containsExactly(EyeState.Open, EyeState.Closed, EyeState.Open).inOrder()
        assertThat(summary.closed).isEqualTo(1)
        assertThat(summary.verdict).isEqualTo(EyeState.Closed)
        assertThat(summary.focus).isSameInstanceAs(three.faces[1])
        assertThat(flagsOf(three, default)).contains(ShotFlag.EyesClosed)
        assertThat(eyesOpen(three, Sensitivity.Medium)).isFalse()
        val half = analysis(faces = listOf(face(0.4)))
        assertThat(eyesSummary(half, Sensitivity.Medium).verdict).isEqualTo(EyeState.Open)
        assertThat(eyesSummary(half, Sensitivity.High).verdict).isEqualTo(EyeState.Closed)
        assertThat(eyesSummary(analysis(faces = listOf(face(0.05, false))), Sensitivity.Medium).verdict).isEqualTo(EyeState.Unknown)
        assertThat(eyesSummary(analysis(faces = listOf(face(0.05).copy(leftEyeOpen = null))), Sensitivity.Medium).states).containsExactly(EyeState.Unknown)
        val none = eyesSummary(analysis(), Sensitivity.Medium)
        assertThat(none.verdict).isEqualTo(EyeState.Unknown)
        assertThat(none.focus).isNull()
    }

    @Test
    fun `filters by flag`() {
        assertThat(matchesAssist(listOf(ShotFlag.Blurry), AssistFilter.Only(ShotFlag.Blurry))).isTrue()
        assertThat(matchesAssist(listOf(ShotFlag.Blurry), AssistFilter.Only(ShotFlag.EyesClosed))).isFalse()
        assertThat(matchesAssist(emptyList(), AssistFilter.Clean)).isTrue()
        assertThat(matchesAssist(emptyList(), AssistFilter.Flagged)).isFalse()
        assertThat(matchesAssist(null, AssistFilter.Clean)).isFalse()
        assertThat(matchesAssist(null, AssistFilter.Any)).isTrue()
    }

    @Test
    fun `hamming and colour distances`() {
        assertThat(hammingDistance(hash, hash)).isEqualTo(0)
        assertThat(hammingDistance(hash, hashWith(7))).isEqualTo(7)
        assertThat(hammingDistance("ffffffffffffffff", "0000000000000000")).isEqualTo(64)
        assertThat(hammingDistance("zz", "00")).isEqualTo(64)
        assertThat(colorDistance(colors, colors)).isEqualTo(0.0)
        assertThat(colorDistance("00".repeat(48), "ff".repeat(48))).isEqualTo(1.0)
        assertThat(colorDistance("00", "0000")).isEqualTo(1.0)
    }

    private val five = listOf("A", "B", "C", "D", "E").map(::shot)

    @Test
    fun `joins frames within the gap, no analysis never splits`() {
        val bursts = groupBursts(five, meta("a" to 0, "b" to 300, "c" to 600, "d" to 5000, "e" to 5400), emptyMap(), 1000)
        assertThat(bursts["a"]).isEqualTo(BurstPosition("a", 0, 3))
        assertThat(bursts["c"]).isEqualTo(BurstPosition("a", 2, 3))
        assertThat(bursts["e"]).isEqualTo(BurstPosition("d", 1, 2))
    }

    @Test
    fun `splits a time run when the scene changes`() {
        val analyses = mapOf(
            "a" to analysis(),
            "b" to analysis(hash = hashWith(3)),
            "c" to analysis(hash = "5e9e0e1e1c1c9fda", colors = "20".repeat(48)),
            "d" to analysis(hash = "5e9e0e1e1c1c9fdb", colors = "20".repeat(48)),
        )
        val bursts = groupBursts(five.take(4), meta("a" to 0, "b" to 200, "c" to 600, "d" to 800), analyses, 1000)
        assertThat(bursts["a"]?.size).isEqualTo(2)
        assertThat(bursts["c"]).isEqualTo(BurstPosition("c", 0, 2))
    }

    @Test
    fun `colours alone can split, a small hash change with the same light does not`() {
        val analyses = mapOf(
            "a" to analysis(),
            "b" to analysis(hash = hashWith(22), colors = "80".repeat(47) + "84"),
            "c" to analysis(colors = "f0".repeat(48)),
        )
        val bursts = groupBursts(five.take(3), meta("a" to 0, "b" to 200, "c" to 400), analyses, 1000)
        assertThat(bursts["b"]).isEqualTo(BurstPosition("a", 1, 2))
        assertThat(bursts).doesNotContainKey("c")
    }

    @Test
    fun `joins near-identical frames a little over the gap, never far over it`() {
        val analyses = mapOf("a" to analysis(), "b" to analysis(hash = hashWith(5)), "c" to analysis(hash = hashWith(6)), "d" to analysis(hash = hashWith(20)))
        val times = meta("a" to 0, "b" to 3000, "c" to 10_000, "d" to 12_500)
        val bursts = groupBursts(five.take(4), times, analyses, 1000)
        assertThat(bursts["b"]).isEqualTo(BurstPosition("a", 1, 2))
        assertThat(bursts).doesNotContainKey("c")
        assertThat(bursts).doesNotContainKey("d")
        assertThat(groupBursts(five.take(4), times, analyses, 3000)["d"]?.size).isEqualTo(4)
    }

    @Test
    fun `missing or backwards times never join, everything one burst`() {
        assertThat(groupBursts(five.take(3), meta("a" to 1000, "b" to 500), emptyMap(), 1000)).isEmpty()
        val all = (0 until 320).map { shot("DSC" + it.toString().padStart(5, '0')) }
        val times = all.withIndex().associate { (i, s) -> s.id to ShotMetadata(captureMillis = i * 100L) }
        assertThat(groupBursts(all, times, emptyMap(), 1000)[all[319].id]).isEqualTo(BurstPosition(all[0].id, 319, 320))
    }

    private val rankShots = listOf("DSC1", "DSC2", "DSC3", "DSC4", "DSC5").map(::shot)
    private val rankBursts = mapOf("dsc1" to BurstPosition("dsc1", 0, 3), "dsc2" to BurstPosition("dsc1", 1, 3), "dsc3" to BurstPosition("dsc1", 2, 3))
    private val rankAnalyses = mapOf(
        "dsc1" to analysis(subject = 120.0, faces = listOf(face(0.9))),
        "dsc2" to analysis(subject = 300.0, faces = listOf(face(0.05))),
        "dsc3" to analysis(subject = 280.0, faces = listOf(face(0.95))),
        "dsc4" to analysis(blur = 0.7),
        "dsc5" to analysis(),
    )

    @Test
    fun `ranks by relative sharpness minus penalties, ties in capture order`() {
        val ranked = rankBurst(listOf("dsc1", "dsc2", "dsc3"), rankAnalyses, default)
        assertThat(ranked.map { it.shotId }).containsExactly("dsc3", "dsc1", "dsc2").inOrder()
        assertThat(ranked[0].relativeSharpness).isWithin(1e-9).of(280.0 / 300)
        assertThat(ranked[2].flags).containsExactly(ShotFlag.EyesClosed)
        val tie = mapOf("x" to analysis(subject = 100.0), "y" to analysis(subject = 100.0))
        assertThat(rankBurst(listOf("x", "y"), tie, default)[0].shotId).isEqualTo("x")
        assertThat(bestInBursts(rankShots, rankBursts, rankAnalyses, default)["dsc1"]).isEqualTo("dsc3")
    }

    @Test
    fun `penalises flagged frames and never crowns a frame with a problem`() {
        val frames = mapOf("a" to analysis(subject = 300.0, faces = listOf(face(0.05)), blownArea = 0.12), "b" to analysis(subject = 210.0, faces = listOf(face(0.9))))
        val ranked = rankBurst(listOf("a", "b"), frames, default)
        assertThat(ranked.map { it.shotId }).containsExactly("b", "a").inOrder()
        assertThat(ranked[1].score).isWithin(1e-9).of(1 - 1 - 0.4)
        val allBad = mapOf("a" to analysis(subject = 300.0, faces = listOf(face(0.05)), blownArea = 0.12), "b" to analysis(subject = 200.0, blownArea = 0.12))
        val pair = listOf(shot("A"), shot("B"))
        val positions = mapOf("a" to BurstPosition("a", 0, 2), "b" to BurstPosition("a", 1, 2))
        assertThat(rankBurst(listOf("a", "b"), allBad, default)[0].shotId).isEqualTo("b")
        assertThat(bestInBursts(pair, positions, allBad, default)).isEmpty()
        assertThat(bestInBursts(pair, positions, frames, default)["a"]).isEqualTo("b")
    }

    @Test
    fun `keeps the best of a burst, rejects the rest with reasons and flagged singles`() {
        val s = suggest(rankShots, rankBursts, rankAnalyses, emptyMap(), default)
        assertThat(s["dsc3"]).isEqualTo(Suggestion("dsc3", SuggestionAction.Keep, listOf(SuggestionReason.Sharpest, SuggestionReason.EyesOpen), "dsc1"))
        assertThat(s["dsc1"]?.action).isEqualTo(SuggestionAction.Reject)
        assertThat(s["dsc1"]?.reasons).containsExactly(SuggestionReason.BlurrierThan("DSC3"))
        assertThat(s["dsc2"]?.reasons).containsExactly(SuggestionReason.FlagReason(ShotFlag.EyesClosed))
        assertThat(s["dsc4"]?.reasons).containsExactly(SuggestionReason.FlagReason(ShotFlag.Blurry))
        assertThat(s).doesNotContainKey("dsc5")
    }

    @Test
    fun `never suggests over a decision, waits for a whole burst, keeps none when all are flagged`() {
        val s = suggest(rankShots, rankBursts, rankAnalyses, mapOf("dsc3" to Decision(Flag.Reject)), default)
        assertThat(s).doesNotContainKey("dsc3")
        assertThat(s["dsc1"]?.action).isEqualTo(SuggestionAction.Reject)
        val partial = rankAnalyses - "dsc2"
        val p = suggest(rankShots, rankBursts, partial, emptyMap(), default)
        assertThat(listOf("dsc1", "dsc2", "dsc3").any { it in p }).isFalse()
        val blurry = listOf("dsc1", "dsc2", "dsc3").withIndex().associate { (i, id) -> id to analysis(blur = 0.7, subject = 100.0 + i) }
        val b = suggest(rankShots.take(3), rankBursts, blurry, emptyMap(), default)
        assertThat(b.values.all { it.action == SuggestionAction.Reject }).isTrue()
        assertThat(b["dsc3"]?.reasons).contains(SuggestionReason.AllFlagged)
    }

    @Test
    fun `compare opens on the burst's top two, else the next shot`() {
        val members = burstMembers(rankShots, rankBursts)
        val order = rankShots.map { it.id }
        assertThat(comparePair("dsc1", order, rankBursts, members, rankAnalyses, default)).isEqualTo("dsc3" to "dsc1")
        assertThat(comparePair("dsc5", order, rankBursts, members, rankAnalyses, default)).isEqualTo("dsc5" to "dsc4")
        assertThat(comparePair("dsc4", listOf("dsc4"), rankBursts, members, rankAnalyses, default)).isNull()
    }

    @Test
    fun `sorts sharpest first, unanalysed last in their order`() {
        val shots = listOf("A", "B", "C", "D").map(::shot)
        val analyses = mapOf("b" to analysis(blur = 0.5), "d" to analysis(blur = 0.2))
        assertThat(sortBySharpness(shots, analyses).map { it.id }).containsExactly("d", "b", "a", "c").inOrder()
    }

    @Test
    fun `collapses bursts into one cell on their cover, expands on demand`() {
        val shots = listOf("A", "B", "C", "D").map(::shot)
        val bursts = mapOf("b" to BurstPosition("b", 0, 3), "c" to BurstPosition("b", 1, 3), "d" to BurstPosition("b", 2, 3))
        val covers = mapOf("b" to "c")
        assertThat(gridItems(shots, bursts, covers, emptySet(), true)).containsExactly(GridItem.Single("a", 0), GridItem.Stack("b", "c", 2, 3)).inOrder()
        assertThat(gridItems(shots, bursts, covers, setOf("b"), true)).hasSize(4)
        assertThat(gridItems(shots, bursts, covers, emptySet(), false)).hasSize(4)
        assertThat(gridItems(shots.take(2), bursts, covers, emptySet(), true)).containsExactly(GridItem.Single("a", 0), GridItem.Single("b", 1)).inOrder()
    }
}
