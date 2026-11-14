package dev.hfps.hoard.app.cull

/*
 * Burst mode (ADR 0008 "Burst mode", 1.7.0), ported from src/features/cull/burst.ts with its tests (BurstTest): which
 * frame a burst shows, whether it is resolved, how the folder viewer steps over a burst as one item, "Keep this one"
 * and ✕ on a whole burst (1.10.1).
 */

/** A frame the user kept explicitly: picked, or given stars. */
fun keptExplicitly(d: Decision?) = d?.flag == Flag.Pick || (d?.rating ?: 0) > 0

/** The frame a burst shows: the first picked frame, else the crowned best (unless rejected), else the first not rejected. */
fun burstCover(ids: List<String>, decisions: Decisions, best: String?): String {
    ids.firstOrNull { decisions[it]?.flag == Flag.Pick }?.let { return it }
    if (best != null && best in ids && decisions[best]?.flag != Flag.Reject) return best
    return ids.firstOrNull { decisions[it]?.flag != Flag.Reject } ?: ids.first()
}

data class BurstStatus(val size: Int, val kept: Int, val decided: Int, val resolved: Boolean)

fun burstStatus(ids: List<String>, decisions: Decisions): BurstStatus {
    var kept = 0
    var decided = 0
    for (id in ids) {
        val flag = decisions[id]?.flag
        if (flag != null) decided++
        if (flag == Flag.Pick) kept++
    }
    return BurstStatus(ids.size, kept, decided, ids.isNotEmpty() && decided == ids.size)
}

/**
 * "Keep this one": picks [keep] and rejects the other frames, as one undo step. Explicit keepers (picks, stars) are
 * left alone; frames already rejected stay rejected; [keep] keeps its stars. Null when nothing would change.
 */
fun keepThisOne(decisions: Decisions, ids: List<String>, keep: String): DecisionChange? {
    if (keep !in ids) return null
    return changeOf(decisions, ids) { d, id ->
        when {
            id == keep -> d.withFlag(Flag.Pick)
            keptExplicitly(d) -> d
            else -> d.withFlag(Flag.Reject)
        }
    }
}

enum class RejectMode { Toggle, Set }

/**
 * ✕ on a burst in the folder viewer (1.10.1): rejects every frame, picks included, as one undo step (stars kept).
 * [RejectMode.Toggle] unmarks every frame instead when they are all rejected already.
 */
fun rejectBurst(decisions: Decisions, ids: List<String>, mode: RejectMode): DecisionChange? {
    val allRejected = ids.isNotEmpty() && ids.all { decisions[it]?.flag == Flag.Reject }
    val flag = if (mode == RejectMode.Toggle && allRejected) null else Flag.Reject
    return changeOf(decisions, ids) { d, _ -> d.withFlag(flag) }
}

/** One step of the viewer: a single shot, or a whole burst (shown by its cover). */
sealed interface ViewerStep {
    data class Single(val shotId: String) : ViewerStep
    data class Burst(val burst: String, val shotIds: List<String>) : ViewerStep
}

/**
 * The folder viewer's steps for [order]: with [collapse], a burst whose frames are all in [order] becomes one step
 * where its first frame was. Without, one step per shot.
 */
fun viewerSteps(order: List<String>, bursts: Map<String, BurstPosition>, members: Map<String, List<String>>, collapse: Boolean): List<ViewerStep> {
    if (!collapse) return order.map { ViewerStep.Single(it) }
    val inOrder = order.toHashSet()
    val steps = ArrayList<ViewerStep>()
    val seen = HashSet<String>()
    for (shotId in order) {
        val burst = bursts[shotId]?.burst
        val ids = burst?.let { members[it] }
        if (burst == null || ids == null || ids.size < 2 || !ids.all { it in inOrder }) {
            steps += ViewerStep.Single(shotId)
            continue
        }
        if (!seen.add(burst)) continue
        steps += ViewerStep.Burst(burst, ids.toList())
    }
    return steps
}

/** The step showing [shotId] (its own, or its burst's), or -1. */
fun stepOf(steps: List<ViewerStep>, shotId: String): Int = steps.indexOfFirst {
    when (it) {
        is ViewerStep.Single -> it.shotId == shotId
        is ViewerStep.Burst -> shotId in it.shotIds
    }
}

/** The shot a step shows now (a burst: its cover, which follows the decisions). */
fun stepShot(step: ViewerStep, decisions: Decisions, best: Map<String, String>): String = when (step) {
    is ViewerStep.Single -> step.shotId
    is ViewerStep.Burst -> burstCover(step.shotIds, decisions, best[step.burst])
}

/** Quick compare's other frame in burst mode: the top-ranked other frame, else the next (or previous) frame. */
fun comparePartner(current: String, ids: List<String>, ranked: List<String>): String? {
    ranked.firstOrNull { it != current && it in ids }?.let { return it }
    val at = ids.indexOf(current)
    return ids.getOrNull(at + 1) ?: ids.getOrNull(at - 1)
}
