package dev.hfps.hoard.app.cull

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import org.junit.Test
import java.time.Instant

/** Port of src/features/cull/xmp.test.ts, plus the analysis cache's JSON round trip. */
class CullXmpTest {
    private fun shot(stem: String, raw: Boolean, jpeg: Boolean) = Shot(
        id = stem.lowercase(),
        stem = stem,
        raw = if (raw) ShotFile("$stem.ARW", "D/$stem.ARW", 10, null, 0) else null,
        rendered = if (jpeg) ShotFile("$stem.JPG", "D/$stem.JPG", 5, null, 0) else null,
        others = emptyList(),
    )

    @Test
    fun `puts decisions on the wire, unmarked as none and a reject with its stars`() {
        assertThat(Decision().toXmp()).isEqualTo(XmpFields(0, XmpFlag.NONE))
        assertThat(Decision(Flag.Reject, 3).toXmp()).isEqualTo(XmpFields(3, XmpFlag.REJECT))
    }

    @Test
    fun `asks Copy picks for one sidecar per copied shot, on the RAW when it is copied`() {
        val order = listOf(shot("A", true, true), shot("B", false, true), shot("C", true, true))
        val decisions = mapOf("a" to Decision(Flag.Pick, 5), "b" to Decision(Flag.Pick, 0), "c" to Decision(Flag.Reject, 0))
        assertThat(sidecarsFor(planCopy(order, decisions, CopyFiles.Both).items, order, decisions))
            .containsExactly("D/A.ARW", XmpFields(5, XmpFlag.PICK), "D/B.JPG", XmpFields(0, XmpFlag.PICK)).inOrder()
        assertThat(sidecarsFor(planCopy(order, decisions, CopyFiles.Jpeg).items, order, decisions).keys).containsExactly("D/A.JPG", "D/B.JPG").inOrder()
    }

    @Test
    fun `gives Reveal the decision of every file sent whose shot has one`() {
        val decisions = mapOf("dsc1" to Decision(Flag.Pick, 4), "dsc2" to Decision(Flag.Reject, 2), "dsc3" to Decision())
        assertThat(revealCullingFor(listOf("D/DSC1.ARW", "D/dsc1.jpg", "D/DSC2.JPG", "D/DSC3.JPG", "D/DSC4.ARW"), decisions)).containsExactly(
            "D/DSC1.ARW", XmpFields(4, XmpFlag.PICK),
            "D/dsc1.jpg", XmpFields(4, XmpFlag.PICK),
            "D/DSC2.JPG", XmpFields(2, XmpFlag.REJECT),
        )
    }

    @Test
    fun `precedence follows ADR 0011`() {
        assertThat(importAfter(sidecarStateOf(null, null, null, null))).isNull()
        assertThat(importAfter(sidecarStateOf(5_000, 4_000, true, null))).isEqualTo(4_000L)
        assertThat(importAfter(sidecarStateOf(5_000, 4_000, false, null))).isEqualTo(5_000L)
        assertThat(importAfter(sidecarStateOf(null, null, null, "2026-11-01T00:00:00.000Z"))).isEqualTo(Instant.parse("2026-11-01T00:00:00.000Z").toEpochMilli())
        assertThat(importAfter(sidecarStateOf(1_000, 9_000, false, null))).isEqualTo(9_000L)
    }

    @Test
    fun `imports what the sidecar says, keeps a reject's stars, ignores develop-only sidecars`() {
        val current = Decision(Flag.Reject, 3)
        assertThat(importedDecision(current, XmpFields(0, XmpFlag.REJECT), true)).isEqualTo(current)
        assertThat(importedDecision(current, XmpFields(5, XmpFlag.PICK), true)).isEqualTo(Decision(Flag.Pick, 5))
        assertThat(importedDecision(current, XmpFields(2, XmpFlag.NONE, "Red"), true)).isEqualTo(Decision(null, 2))
        assertThat(importedDecision(current, XmpFields(0, XmpFlag.NONE), false)).isNull()
        assertThat(importChanges(current, current)).isFalse()
    }

    @Test
    fun `analysis JSON round-trips and reads Hoard 1's rows`() {
        val hoard1 = """{"version":2,"source":"rawPreview","width":1536,"height":1024,"millis":180,
            "sharpness":{"global":12.5,"tiles":[1,2,3],"bestTile":2,"subject":40.25,"subjectKind":"face","subjectBlur":0.4123},
            "facesDetected":true,"faces":[{"x":0.1,"y":0.2,"width":0.3,"height":0.4,"leftEyeOpen":0.9,"prominent":true,"sharpness":30,"blur":0.3}],
            "exposure":{"histogram":[0.5,0.5],"mean":0.45,"highlights":0.01,"shadows":0.02,"blownArea":0,"faceHighlights":0},
            "hash":"a161f1e1e3e36025","colors":"8080"}"""
        val a = AnalysisJson.read(hoard1)!!
        assertThat(a.sharpness.subjectBlur).isEqualTo(0.4123)
        assertThat(a.faces.single().rightEyeOpen).isNull()
        assertThat(a.faces.single().leftEyeOpen).isEqualTo(0.9)
        assertThat(AnalysisJson.read(AnalysisJson.write(a))).isEqualTo(a)
        assertThat(AnalysisJson.read("{nope")).isNull()
    }
}
