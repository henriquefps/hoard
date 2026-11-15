package dev.hfps.hoard.app.cull

import dev.hfps.hoard.app.files.photoStem
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import java.time.Instant

/*
 * Culling's side of the XMP sidecars (ADR 0011), ported from src/features/cull/xmp.ts with its tests (CullXmpTest):
 * decisions ↔ sidecar fields, what "Copy picks" asks the transfer engine to write, Reveal's CULLING values, and the
 * precedence rule that decides whether a sidecar changed elsewhere wins over Hoard's database.
 */

/** A decision as sidecar fields (`none` = unmarked; a reject keeps its stars here). */
fun Decision.toXmp(): XmpFields = XmpFields(rating.coerceIn(0, 5), when (flag) {
    Flag.Pick -> XmpFlag.PICK
    Flag.Reject -> XmpFlag.REJECT
    null -> XmpFlag.NONE
})

fun XmpFields.sameAs(d: Decision): Boolean = this.rating == d.rating && this.flag == d.toXmp().flag

/** One sidecar per copied shot: source path (its RAW when copied, else the first file) → fields. Rejects never. */
fun sidecarsFor(items: List<CopyPlanItem>, order: List<Shot>, decisions: Decisions): Map<String, XmpFields> {
    val byShot = LinkedHashMap<String, MutableList<ShotFile>>()
    for (item in items) byShot.getOrPut(item.shotId) { ArrayList() } += item.file
    val shots = order.associateBy { it.id }
    val out = LinkedHashMap<String, XmpFields>()
    for ((shotId, files) in byShot) {
        val shot = shots[shotId] ?: continue
        val decision = decisions.of(shotId)
        if (decision.flag == Flag.Reject) continue
        val target = files.firstOrNull { it.path == shot.raw?.path } ?: files.first()
        out[target.path] = decision.toXmp()
    }
    return out
}

/** Reveal's CULLING values for the files sent: each photo whose shot has a decision gets it (RAW and JPEG alike). */
fun revealCullingFor(paths: List<String>, decisions: Decisions): Map<String, XmpFields> {
    val out = LinkedHashMap<String, XmpFields>()
    for (path in paths) {
        val decision = decisions[photoStem(path.substringAfterLast('/')).lowercase()] ?: continue
        if (decision.unmarked) continue
        out[path] = decision.toXmp()
    }
    return out
}

/** What Hoard knows about a shot's sidecar and decision (the `cull_sidecars` row, plus a pre-1.9.0 decision time). */
data class SidecarState(val decidedAt: Long?, val sidecarMtime: Long?, val synced: Boolean) {
    companion object {
        val NONE = SidecarState(null, null, false)
    }
}

fun sidecarStateOf(decidedAt: Long?, sidecarMtime: Long?, synced: Boolean?, decisionUpdatedAt: String?): SidecarState {
    val legacy = decisionUpdatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    return SidecarState(decidedAt ?: legacy, sidecarMtime, synced == true)
}

/**
 * The mtime a shot's sidecar must be later than to win over Hoard's database, or null when any sidecar wins (Hoard
 * never decided on the shot). See xmp.ts for the reasoning.
 */
fun importAfter(state: SidecarState): Long? {
    if (state.synced && state.sidecarMtime != null) return state.sidecarMtime
    if (state.decidedAt == null) return state.sidecarMtime
    return maxOf(state.decidedAt, state.sidecarMtime ?: Long.MIN_VALUE)
}

/** The decision a newer sidecar brings, or null when it holds no culling value. A reject keeps Hoard's stars. */
fun importedDecision(current: Decision, fields: XmpFields, hasValues: Boolean): Decision? {
    if (!hasValues) return null
    val flag = when (fields.flag) {
        XmpFlag.PICK -> Flag.Pick
        XmpFlag.REJECT -> Flag.Reject
        XmpFlag.NONE -> null
    }
    return Decision(flag, if (flag == Flag.Reject) current.rating else fields.rating.coerceIn(0, 5))
}

fun importChanges(current: Decision, next: Decision) = current != next
