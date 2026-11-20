package dev.hfps.hoard.server.compress

import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressEstimate
import dev.hfps.hoard.server.api.CompressInfo
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.Features

/**
 * What Hoard 2.7.0 offers for a compression target (ADR 0025 Amendment (S2), "Targets"): nothing without the feature
 * `compress`; the action with the server's reason when the target is not usable; the action otherwise. The phone's own
 * replace (`target: "phone"`) is Hoard 2.8.0 and never offered here.
 */
sealed interface CompressOffer {
    /** The server has no `compress` feature: every compression action is hidden. */
    data object Hidden : CompressOffer

    /** Shown, not usable: [reason] is the server's (`no_write_mount`, `no_gpu`, …). */
    data class Unavailable(val reason: String) : CompressOffer

    data object Ready : CompressOffer
}

object CompressGate {
    /** [features] of `GET /v1/server`, its `compress` object, and the target (`nas` or `backup`). */
    fun offer(features: Collection<String>, info: CompressInfo?, target: String): CompressOffer {
        if (Features.COMPRESS !in features || info == null) return CompressOffer.Hidden
        if (target == Compress.TARGET_PHONE) return CompressOffer.Hidden
        val t = when (target) {
            Compress.TARGET_NAS -> info.targets?.nas
            Compress.TARGET_BACKUP -> info.targets?.backup
            else -> null
        }
        // A 0.13.0 server always sends targets; without them only nas can be judged (by `nasWrite` and `available`).
        if (t == null) {
            return if (target == Compress.TARGET_NAS && info.available && info.nasWrite) CompressOffer.Ready else CompressOffer.Unavailable(info.reason ?: REASON_UNKNOWN)
        }
        return if (t.ok) CompressOffer.Ready else CompressOffer.Unavailable(t.reason ?: info.reason ?: REASON_UNKNOWN)
    }

    /**
     * Settings › Compress videos is shown with the feature, and also while the server still reports compression (its
     * `compress` object without the feature: a GPU that went away), so jobs stay visible and restore and purge, which
     * need no GPU, stay reachable.
     */
    fun settingsVisible(features: Collection<String>, info: CompressInfo?): Boolean = Features.COMPRESS in features || info != null

    /** The profiles a sheet offers: "Same look", "Smaller", and AV1 only once the owner turned "Offer AV1" on. */
    fun profiles(info: CompressInfo?, auto: Boolean = false): List<String> {
        val known = info?.profiles?.map { it.id }?.toSet()
        val out = listOf(Compress.PROFILE_SAME, Compress.PROFILE_SMALLER).filter { known == null || known.isEmpty() || it in known }.toMutableList()
        if (!auto && info?.offerAv1 == true && (known.isNullOrEmpty() || Compress.PROFILE_AV1 in known)) out += Compress.PROFILE_AV1
        return out
    }

    const val REASON_UNKNOWN = "unavailable"
}

/**
 * The estimate sheet's figures (ADR 0025 "UI"): "129 of 312 videos · 29.1 GB → about 5.6 GB · about 35 min", skipped by
 * reason, warnings. [totals] cover the whole selection (the server's); warnings come from every page read.
 */
data class EstimateSummary(
    val videos: Int,
    val encode: Int,
    val size: Long,
    val estSize: Long,
    val estMs: Long,
    /** Skip reason → count, the largest first (ties by reason). */
    val skipped: List<Pair<String, Int>>,
    val warnings: Set<String>,
    /** Videos whose probe the server is still reading. */
    val pending: Int,
    /** Of [videos], how many sit in a sub-folder of a chosen folder (0.13.2; 0 = not reported). */
    val subfolders: Int = 0,
) {
    val skippedTotal: Int get() = skipped.sumOf { it.second }
    val saving: Long get() = (size - estSize).coerceAtLeast(0)
    val hdr10Plus: Boolean get() = WARN_HDR10PLUS in warnings
    val metaSaved: Boolean get() = WARN_META in warnings

    companion object {
        const val WARN_HDR10PLUS = "hdr10plus"
        const val WARN_META = "per_frame_metadata"
        const val WARN_PCM = "pcm_audio"
        const val WARN_AV1 = "av1_playback"

        /** The summary of an estimate read page by page ([pages] in order; the last page's totals are the selection's). */
        fun of(pages: List<CompressEstimate>): EstimateSummary {
            val last = pages.lastOrNull() ?: return EstimateSummary(0, 0, 0, 0, 0, emptyList(), emptySet(), 0)
            val totals = last.totals
            val skipped = totals.skip.filter { it.value > 0 }.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            val warnings = pages.flatMap { p -> p.items.filter { it.decision == DECISION_ENCODE }.flatMap { it.warnings } }.toSet()
            return EstimateSummary(totals.items, totals.encode, totals.size, totals.estSize, totals.estMs, skipped, warnings, last.pending, maxOf(totals.subfolders, last.subfolders).coerceAtMost(totals.items))
        }

        const val DECISION_ENCODE = "encode"
    }
}

/**
 * The sheet's state (pure, JVM-tested): the target and profile, the estimate as read so far, and whether Start can be
 * pressed. An automatic sheet ([auto]) starts the folder's standing job; [alsoExisting] adds a one-off job over the
 * videos already there.
 */
data class CompressSheetState(
    val target: String,
    val profiles: List<String>,
    val profile: String = profiles.firstOrNull() ?: Compress.PROFILE_SAME,
    val auto: Boolean = false,
    val alsoExisting: Boolean = false,
    val estimate: EstimateSummary? = null,
    val loading: Boolean = true,
    /** The server's error code or reason when the estimate (or the start) failed. */
    val error: String? = null,
    val starting: Boolean = false,
) {
    /**
     * Start: a selection needs at least one video to encode (or one still pending: the job estimates again); an
     * automatic switch needs no video now (it covers new videos), but "Also compress the N already there" needs them.
     */
    val canStart: Boolean
        get() {
            if (starting || error != null) return false
            if (auto) return !loading || estimate != null
            val e = estimate ?: return false
            return e.encode > 0 || e.pending > 0
        }

    fun withProfile(id: String): CompressSheetState = if (id in profiles && id != profile) copy(profile = id, estimate = null, loading = true, error = null) else this

    fun withEstimate(summary: EstimateSummary): CompressSheetState = copy(estimate = summary, loading = summary.pending > 0, error = null)

    fun failed(reason: String): CompressSheetState = copy(loading = false, error = reason, starting = false)
}

/** Settings › Compress videos (ADR 0025 "UI"): jobs in progress, folders compressed automatically, history. */
data class CompressOverview(
    val running: List<CompressJob>,
    val automatic: List<CompressJob>,
    val history: List<CompressJob>,
) {
    /** Realised: originals emptied from the trash. */
    val saved: Long get() = (running + automatic + history).sumOf { it.bytes.saved }
    val inTrash: Long get() = (running + automatic + history).sumOf { it.bytes.inTrash }
    val inTrashCount: Int get() = (running + automatic + history).sumOf { (it.counts["replaced"] ?: 0) + (it.counts["restored"] ?: 0) }

    companion object {
        /**
         * Splits [jobs] (newest first): an automatic folder's standing job is listed under its folder (never in
         * history) unless cancelled; a job with work left (or paused) is running; the rest is history.
         */
        fun of(jobs: List<CompressJob>): CompressOverview {
            val automatic = jobs.filter { it.auto && it.state != CompressJob.STATE_CANCELLED }
            val rest = jobs.filter { !it.auto }
            val running = rest.filter { !it.finished && (it.paused || it.progress.toDo > 0 || it.progress.current != null) }
            val history = rest - running.toSet()
            return CompressOverview(running, automatic, history)
        }
    }
}

/**
 * Every reason code the server's compression can show Hoard (`server/internal/compress`, ADR 0025 Amendment (2.7.1)),
 * by where it appears. The app maps each to a sentence of its own; an unknown code (a newer server) falls back to a
 * generic sentence with the code in small type.
 */
object CompressReasons {
    /** `GET /v1/server` `compress.reason` and `compress.targets.<t>.reason`: why a target cannot be used. */
    val TARGET = listOf("no_gpu", "no_tools", "no_backup_root", "no_write_mount", "write_mount_mismatch", "write_mount_read_only", "unstable_inode")

    /** Skip reasons of an estimate and of a job's skipped items. */
    val SKIP = listOf(
        "not_video", "unreadable", "already_compressed", "older_compression", "efficient", "small_saving", "dolby_vision",
        "reveal_record", "sync_artifact", "trashed", "busy", "not_in_target", "not_writable",
    )

    /** Failure reasons of a job's items (`verify_*` is a family). */
    val FAILED = listOf(
        "encoder", "source_changed", "no_space", "trash_failed", "not_writable", "unreadable", "output_missing",
        "verify_quality", "verify_metadata", "verify_duration", "verify_streams", "verify_picture", "verify_frames", "verify_decode", "verify_phone",
    )

    /** Errors of a start, a restore or a purge (`POST /v1/compress/…`), besides the target reasons. */
    val ROUTE = listOf("exists", "busy", "state", "not_found", "path_taken", "not_writable", "output_missing", "live_missing")

    fun isVerify(reason: String): Boolean = reason.startsWith("verify")
}
