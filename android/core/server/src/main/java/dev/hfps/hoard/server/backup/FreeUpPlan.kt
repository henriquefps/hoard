package dev.hfps.hoard.server.backup

import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.server.api.BackupVerifyResult
import dev.hfps.hoard.server.api.CandidateDevice
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.BackupRootEntity

/** Settings › Backup › Free up space (owner's decision 3): keep the last 30 days, keep favourites; both on by default. */
data class FreeUpRules(val keepRecent: Boolean = true, val keepFavourites: Boolean = true, val recentDays: Int = FreeUpPlan.RECENT_DAYS)

/** Why a shot of a backed-up folder is not offered (counted in the preview). */
enum class FreeUpExclusion {
    /** Taken in the last 30 days (setting). */
    Recent,
    /** A favourite (`IS_FAVORITE`, setting). */
    Favourite,
    /** A culling decision that has not reached the server yet (pushed first; still pending). */
    Culling,
    /** A file of the shot is not in the server's store or on its NAS yet (or was refused). */
    NotBackedUp,
    /** No file of the shot is a MediaStore row (a RAW or sidecar found by listing only): no system consent can cover it. */
    NoMedia,
}

/**
 * One shot of a backed-up folder, as free up sees it: every file the backup has of it in one folder with one stem
 * (ADR 0014's pairing rule, as the server groups a mirror), [key] the Gallery's phone shot key
 * (`<location>/<dir>/<stem, lower case>`). [taken] is the oldest capture time of its files.
 */
data class FreeShot(val key: String, val rootId: String, val dir: String, val stem: String, val files: List<BackupFileEntity>) {
    val taken: Long get() = files.minOf { it.taken }
    val bytes: Long get() = files.sumOf { it.size }
    /** Files the system's consent deletes (MediaStore rows). */
    val media: List<BackupFileEntity> get() = files.filter { it.mediaId != null }
    /** Files deleted only after the shot's media consent succeeded: sidecars and RAWs found by listing. */
    val others: List<BackupFileEntity> get() = files.filter { it.mediaId == null }
    /** The shot is a video (its main file): counted as such in the preview. */
    val video: Boolean get() = files.none { it.kind == BackupKind.IMAGE || it.kind == BackupKind.RAW } && files.any { it.kind == BackupKind.VIDEO }
    /** The file the preview shows: a still MediaStore lists, else a video. */
    val cover: BackupFileEntity? get() = media.firstOrNull { it.kind == BackupKind.IMAGE } ?: media.firstOrNull()
}

/** Free up's candidates: the shots that may go, oldest first, and how many shots each rule kept. */
data class FreeUpCandidates(val shots: List<FreeShot>, val excluded: Map<FreeUpExclusion, Int>) {
    val bytes: Long get() = shots.sumOf { it.bytes }
}

/**
 * The Reveal gate (ADR 0024 "Free up space"): Reveal 0.9.x and older delete a folder photo, with its versions, when
 * its file leaves the folder, so while a live Reveal on this phone has not declared `freed-adopt`, every shot a live
 * Reveal record names stays, and nothing goes while that Reveal was not seen for 30 minutes.
 */
sealed interface RevealGate {
    /** No live Reveal on this phone, or every live one declared `freed-adopt`. */
    data object Off : RevealGate

    /** Shots any of whose files has `namedByRecord: true` stay. */
    data class On(val lastSeenAt: Long) : RevealGate

    /** That Reveal was last seen more than 30 minutes ago: nothing is freed ("Open Reveal once…"). */
    data class Stale(val lastSeenAt: Long) : RevealGate

    /** Whether the gate lets anything go at all. */
    val blocksAll: Boolean get() = this is Stale
}

/** A verification answer as the phone keeps it: [at] is when the phone received it (yes answers go for 10 minutes). */
data class VerifyAnswer(
    /** `yes`, `pending`, `no` (a refused entry is `no`). */
    val verified: String,
    val at: Long,
    val reason: String? = null,
    val namedByRecord: Boolean = false,
    val refused: Boolean = false,
    /** `pending`: when to ask again (the server's `retryAfter`). */
    val retryAt: Long? = null,
)

/** What the check decided for one shot. */
sealed interface ShotCheck {
    /** Every file verified `yes` in the last 10 minutes, and the Reveal gate lets it go. */
    data object Go : ShotCheck

    /** A file is `pending` (or not asked yet, or its `yes` is older than 10 minutes): ask again. */
    data object Wait : ShotCheck

    /** Stays on the phone, with the reason shown: the server's (`differs`, `missing`, …), `changed`, `reveal`, `reveal_stale`, `refused:<reason>`. */
    data class Keep(val reason: String) : ShotCheck
}

/** What a delete request came to: which files the system really deleted, what else to delete, what to report. */
data class FreeUpSettlement(
    /** Shots whose every media file is gone: their other files (sidecars, listed RAWs) are deleted next. */
    val whole: List<FreeShot>,
    /** Shots of which the system deleted only some media files: those are reported, the rest stays. */
    val partial: List<FreeShot>,
    /** Shots the system deleted nothing of. */
    val kept: List<FreeShot>,
    /** Media files really gone: reported `removed`, reason `freed`. */
    val goneMedia: List<BackupFileEntity>,
    /** Non-media files of [whole] shots to delete now (reported once deleted). */
    val othersToDelete: List<BackupFileEntity>,
)

/**
 * Free up space's decisions, pure and JVM-tested (ADR 0024 "Free up space", phase A3): which shots of the backed-up
 * folders are candidates (whole shots, oldest first, every file backed up; not recent, not a favourite, no pending
 * culling), the Reveal gate from `GET /v1/device`, each shot's outcome from `POST /v1/backup/verify`, and what a
 * (possibly partial) system consent deleted.
 */
object FreeUpPlan {
    const val RECENT_DAYS = 30
    const val DAY_MS = 86_400_000L
    /** A `yes` goes for this long after the phone received it (ADR 0024: "within the last 10 minutes"). */
    const val VERIFY_FRESH_MS = 10 * 60_000L
    /** A Reveal not seen for this long blocks every deletion (its offline edits may not have reached the server). */
    const val REVEAL_STALE_MS = 30 * 60_000L
    /** ADR 0017 "Liveness": an app counts as paired when seen in the last 90 days. */
    const val LIVE_MS = 90 * DAY_MS
    /** `MediaStore.createDeleteRequest` takes at most this many items per request (one consent each). */
    const val MAX_DELETE_REQUEST = 1000
    const val FEATURE_FREED_ADOPT = "freed-adopt"

    /** A file's key: its root and its path on the volume. */
    fun fileKey(f: BackupFileEntity): String = "${f.rootId}|${f.path}"

    /** The server answered for exactly these bytes that it holds them: `stored` / `nas` (or verified since). */
    fun backedUp(f: BackupFileEntity): Boolean =
        f.state in BackupState.DONE && f.serverState in SERVER_HELD && BackupPlan.identified(f) && f.reported == BackupPlan.reportKey(f)

    /** Groups a root's files into shots by folder and stem (lower case), as the Gallery and the server do. */
    fun shots(roots: List<BackupRootEntity>, files: List<BackupFileEntity>): List<FreeShot> {
        val byId = roots.associateBy { it.id }
        return files.groupBy { f ->
            val dir = f.path.substringBeforeLast('/', "")
            Triple(f.rootId, dir, ImageFormats.stem(f.path).lowercase())
        }.mapNotNull { (k, group) ->
            val root = byId[k.first] ?: return@mapNotNull null
            FreeShot("${root.locationId}/${k.second}/${k.third}", k.first, k.second, ImageFormats.stem(group.first().path), group.sortedBy { it.path })
        }
    }

    /**
     * The candidates, oldest first: every file of the shot backed up ([backedUp]), at least one MediaStore file, not
     * taken in the last [FreeUpRules.recentDays] days (when kept), no favourite file (when kept; [favourites] are
     * `device_media` ids), no pending culling ([pendingCulling]: shot keys). The Reveal gate is applied after the
     * verification ([check]), where `namedByRecord` is known.
     */
    fun candidates(
        roots: List<BackupRootEntity>,
        files: List<BackupFileEntity>,
        rules: FreeUpRules,
        favourites: Set<Long>,
        pendingCulling: Set<String>,
        now: Long,
    ): FreeUpCandidates {
        val excluded = LinkedHashMap<FreeUpExclusion, Int>()
        fun keep(why: FreeUpExclusion) = excluded.merge(why, 1, Int::plus)
        val out = ArrayList<FreeShot>()
        val since = now - rules.recentDays * DAY_MS
        for (shot in shots(roots.filter { !it.paused }, files)) {
            when {
                shot.files.any { !backedUp(it) } -> keep(FreeUpExclusion.NotBackedUp)
                shot.media.isEmpty() -> keep(FreeUpExclusion.NoMedia)
                rules.keepRecent && shot.files.any { it.taken >= since } -> keep(FreeUpExclusion.Recent)
                rules.keepFavourites && shot.media.any { it.mediaId in favourites } -> keep(FreeUpExclusion.Favourite)
                shot.key in pendingCulling -> keep(FreeUpExclusion.Culling)
                else -> out += shot
            }
        }
        return FreeUpCandidates(out.sortedWith(compareBy({ it.taken }, { it.key })), excluded)
    }

    /** Whether a device of `physicalDevice.devices` is Reveal (any build: `reveal`, `reveal-debug`, …). */
    fun isReveal(d: CandidateDevice): Boolean = d.app == "reveal" || d.app?.startsWith("reveal-") == true

    /**
     * The gate from this phone's `physicalDevice.devices` (`GET /v1/device`): live Reveals (seen in the last 90 days)
     * that did not declare `freed-adopt` close it; one of them not seen for 30 minutes blocks everything.
     */
    fun revealGate(devices: List<CandidateDevice>, now: Long): RevealGate {
        val live = devices.filter { isReveal(it) && it.lastSeenAt != null && now - it.lastSeenAt <= LIVE_MS }
        val gating = live.filter { FEATURE_FREED_ADOPT !in it.features }
        if (gating.isEmpty()) return RevealGate.Off
        val oldest = gating.minOf { it.lastSeenAt!! }
        return if (now - oldest > REVEAL_STALE_MS) RevealGate.Stale(oldest) else RevealGate.On(gating.maxOf { it.lastSeenAt!! })
    }

    /** A verify result as the phone keeps it (received at [now]). */
    fun answer(r: BackupVerifyResult, now: Long): VerifyAnswer {
        val refused = r.state == "refused"
        val verified = when {
            refused -> "no"
            r.verified == "yes" || r.verified == "pending" -> r.verified
            else -> "no"
        }
        return VerifyAnswer(
            verified = verified,
            at = now,
            reason = when {
                refused -> "refused:${r.reason ?: "invalid"}"
                verified == "no" -> r.reason ?: "unknown"
                else -> null
            },
            namedByRecord = r.namedByRecord,
            refused = refused,
            retryAt = if (verified == "pending") now + (r.retryAfter?.takeIf { it > 0 } ?: DEFAULT_RETRY_S) * 1000 else null,
        )
    }

    /**
     * One shot's outcome from its files' answers ([answers] by [fileKey]) under [gate]. A `no` (or a refusal) keeps it
     * with the server's reason; so does a record of a gating Reveal; a `pending`, a missing answer or a `yes` older than
     * 10 minutes waits; otherwise it goes.
     */
    fun check(shot: FreeShot, answers: Map<String, VerifyAnswer>, gate: RevealGate, now: Long): ShotCheck {
        if (gate is RevealGate.Stale) return ShotCheck.Keep(REASON_REVEAL_STALE)
        val mine = shot.files.map { answers[fileKey(it)] }
        mine.firstOrNull { it != null && it.verified == "no" }?.let { return ShotCheck.Keep(it.reason ?: "unknown") }
        if (gate is RevealGate.On && mine.any { it?.namedByRecord == true }) return ShotCheck.Keep(REASON_REVEAL)
        if (mine.any { it == null || it.verified != "yes" || now - it.at > VERIFY_FRESH_MS }) return ShotCheck.Wait
        return ShotCheck.Go
    }

    /** Files whose answer must be asked (again) now: none yet, a `pending` that is due, a `yes` no longer fresh. */
    fun toAsk(shots: List<FreeShot>, answers: Map<String, VerifyAnswer>, now: Long): List<BackupFileEntity> = shots.flatMap { s ->
        s.files.filter { f ->
            val a = answers[fileKey(f)]
            a == null || (a.verified == "pending" && (a.retryAt ?: 0L) <= now) || (a.verified == "yes" && now - a.at > VERIFY_FRESH_MS)
        }
    }

    /** The shots to delete split into system requests of at most [MAX_DELETE_REQUEST] media files, whole shots per request. */
    fun requests(shots: List<FreeShot>, max: Int = MAX_DELETE_REQUEST): List<List<FreeShot>> {
        val out = ArrayList<List<FreeShot>>()
        var current = ArrayList<FreeShot>()
        var count = 0
        for (s in shots) {
            val n = s.media.size
            if (count + n > max && current.isNotEmpty()) {
                out += current
                current = ArrayList()
                count = 0
            }
            current += s
            count += n
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * What a delete request deleted: [gone] are the [fileKey]s of the media files the system no longer has (checked
     * after the dialog). A refused or partial consent frees what the system deleted, no more; a shot's other files go
     * only when all its media went.
     */
    fun settle(shots: List<FreeShot>, gone: Set<String>): FreeUpSettlement {
        val whole = ArrayList<FreeShot>()
        val partial = ArrayList<FreeShot>()
        val kept = ArrayList<FreeShot>()
        val goneMedia = ArrayList<BackupFileEntity>()
        val others = ArrayList<BackupFileEntity>()
        for (s in shots) {
            val deleted = s.media.filter { fileKey(it) in gone }
            goneMedia += deleted
            when (deleted.size) {
                0 -> kept += s
                s.media.size -> {
                    whole += s
                    others += s.others
                }
                else -> partial += s
            }
        }
        return FreeUpSettlement(whole, partial, kept, goneMedia, others)
    }

    /** "2,340 photos and 112 videos": the candidates' counts by kind. */
    fun counts(shots: List<FreeShot>): Pair<Int, Int> = shots.count { !it.video } to shots.count { it.video }

    const val REASON_REVEAL = "reveal"
    const val REASON_REVEAL_STALE = "reveal_stale"
    /** The file is not as it was hashed and reported (the next backup run reports it again). */
    const val REASON_CHANGED = "changed"
    /** The file is not on the phone any more (or unreadable) when checked. */
    const val REASON_UNREADABLE = "unreadable"
    const val DEFAULT_RETRY_S = 30L
    private val SERVER_HELD = setOf("stored", "nas")
}
