package dev.hfps.hoard.app.cull

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import dev.hfps.hoard.xmp.XmpRead
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
    fun `a first read keeps Hoard's values and fills only its default fields`() {
        // Lightroom's Rating=1 on a 4-star shot: Hoard keeps its 4, whatever the sidecar's mtime.
        assertThat(importFromSidecar(Decision(null, 4), XmpFields(1), last = null, newer = true)).isEqualTo(Decision(null, 4))
        // A pick without stars gets the sidecar's stars; a rated shot gets the sidecar's flag.
        assertThat(importFromSidecar(Decision(Flag.Pick, 0), XmpFields(3), null, newer = false)).isEqualTo(Decision(Flag.Pick, 3))
        assertThat(importFromSidecar(Decision(null, 2), XmpFields(5, XmpFlag.REJECT), null, newer = false)).isEqualTo(Decision(Flag.Reject, 2))
        // All defaults in Hoard: the sidecar comes in whole.
        assertThat(importFromSidecar(Decision.UNMARKED, XmpFields(4, XmpFlag.PICK), null, newer = false)).isEqualTo(Decision(Flag.Pick, 4))
        // A sidecar of defaults (develop settings only) fills nothing.
        assertThat(importFromSidecar(Decision(Flag.Reject, 3), XmpFields(), null, newer = true)).isEqualTo(Decision(Flag.Reject, 3))
        assertThat(valuesOf(XmpRead(XmpFields(2, XmpFlag.PICK), hasValues = false))).isEqualTo(XmpFields())
    }

    @Test
    fun `a later read applies only the fields changed since the last read, when newer than Hoard's decision`() {
        val last = XmpFields(3, XmpFlag.PICK)
        // Rating changed in Lightroom, flag unchanged: only the stars apply (Hoard's reject stays).
        assertThat(importFromSidecar(Decision(Flag.Reject, 5), XmpFields(1, XmpFlag.PICK), last, newer = true)).isEqualTo(Decision(Flag.Reject, 1))
        // Not newer than Hoard's decision: nothing applies.
        assertThat(importFromSidecar(Decision(Flag.Reject, 5), XmpFields(1, XmpFlag.PICK), last, newer = false)).isEqualTo(Decision(Flag.Reject, 5))
        // Nothing changed but the label: Hoard's newer edits stay.
        assertThat(importFromSidecar(Decision(null, 5), XmpFields(3, XmpFlag.PICK, "Red"), last, newer = true)).isEqualTo(Decision(null, 5))
        // A reject keeps Hoard's stars; un-rejecting brings the sidecar's.
        assertThat(importFromSidecar(Decision(Flag.Pick, 4), XmpFields(0, XmpFlag.REJECT), last, newer = true)).isEqualTo(Decision(Flag.Reject, 4))
        assertThat(importFromSidecar(Decision(Flag.Reject, 4), XmpFields(2, XmpFlag.NONE), XmpFields(0, XmpFlag.REJECT), newer = true)).isEqualTo(Decision(null, 2))
    }

    @Test
    fun `a sidecar holds a decision when its flag and stars are Hoard's, a reject's stars aside`() {
        assertThat(XmpFields(3, XmpFlag.PICK).holds(Decision(Flag.Pick, 3))).isTrue()
        assertThat(XmpFields(0, XmpFlag.REJECT).holds(Decision(Flag.Reject, 4))).isTrue()
        assertThat(XmpFields(1).holds(Decision(null, 4))).isFalse()
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
