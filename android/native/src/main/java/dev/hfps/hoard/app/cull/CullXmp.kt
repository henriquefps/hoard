package dev.hfps.hoard.app.cull

import dev.hfps.hoard.app.files.photoStem
import dev.hfps.hoard.smb.cull.SidecarStamp
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import dev.hfps.hoard.xmp.XmpRead
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

/**
 * What Hoard last read from a shot's sidecar (the `cull_sidecar_reads` row; ADR 0011 "First read of a sidecar"):
 * which file, its size and mtime, and the culling values read (defaults when it held none).
 */
data class SidecarSeen(val stamp: SidecarStamp, val fields: XmpFields)

/** The culling values a read brings: a sidecar without culling values (develop settings only) holds the defaults. */
fun valuesOf(read: XmpRead): XmpFields = if (read.hasValues) read.fields else XmpFields()

/** A sidecar's stars as a value: a reject holds none (`Rating=-1`; its stars stay in Hoard only). */
private fun starsOf(fields: XmpFields): Int? = if (fields.flag == XmpFlag.REJECT) null else fields.rating.coerceIn(0, 5)

private fun flagOf(flag: XmpFlag): Flag? = when (flag) {
    XmpFlag.PICK -> Flag.Pick
    XmpFlag.REJECT -> Flag.Reject
    XmpFlag.NONE -> null
}

/**
 * The decision after reading a sidecar's [read] values (ADR 0011, "First read of a sidecar"; the same rule as
 * hoard-server 0.10.1 and Reveal). Hoard has no colour label, so only stars and the flag apply.
 *
 * - **First read** ([last] null: Hoard never read this sidecar, or forgot it when it disappeared): a field Hoard holds
 *   a value for (stars other than 0, a pick or a reject) is kept whatever the sidecar's mtime; only default fields are
 *   filled from the sidecar.
 * - **Later reads** (the file changed since [last]): only the fields whose value differs from [last] are candidates,
 *   and they apply when the sidecar is [newer] than Hoard's decision ([importAfter]).
 *
 * A reject keeps Hoard's stars (the file cannot hold them).
 */
fun importFromSidecar(current: Decision, read: XmpFields, last: XmpFields?, newer: Boolean): Decision {
    var flag = current.flag
    var rating = current.rating
    val stars = starsOf(read)
    if (last == null) {
        if (flag == null) flag = flagOf(read.flag)
        if (rating == 0 && stars != null) rating = stars
    } else if (newer) {
        if (read.flag != last.flag) flag = flagOf(read.flag)
        if (stars != null && stars != starsOf(last)) rating = stars
    }
    return Decision(flag, rating)
}

/** Whether the sidecar's [this] values are Hoard's [d] (a reject's stars aside): the sidecar then holds Hoard's decision. */
fun XmpFields.holds(d: Decision): Boolean {
    val fields = d.toXmp()
    return flag == fields.flag && (flag == XmpFlag.REJECT || rating == fields.rating)
}
