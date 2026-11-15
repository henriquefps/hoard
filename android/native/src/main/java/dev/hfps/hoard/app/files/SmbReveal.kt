package dev.hfps.hoard.app.files

import dev.hfps.hoard.server.paths.FileMatch

/**
 * "Edit in Reveal" from Files and folder culling on an SMB share (ADR 0022, ADR 0010 "Server items"): per shot, a
 * server reference when the paired server holds it and Reveal takes references (≥ 0.9.0), else URIs as before. Pure
 * and JVM-tested (`SmbRevealTest`).
 */
object SmbReveal {
    /** On-demand lookups per hand-off (each reads 2 MiB of the share): shots beyond go as URIs. */
    const val MAX_LOOKUPS = 20

    /** One shot of the hand-off: its files in Reveal's order (RAW first), from one folder and one stem. */
    data class Shot(val key: String, val paths: List<String>) {
        val main: String get() = paths.first()
    }

    /** The shot as a server item ([itemId]) and the path it was matched by. */
    data class ServerShot(val shot: Shot, val itemId: String, val matchedPath: String, val fingerprint: String?)

    data class Plan(
        /** Shots sent by reference, in the hand-off's order. */
        val server: List<ServerShot>,
        /** Paths sent as URIs (photos of unmapped shots), in the hand-off's order. */
        val uris: List<String>,
        /** Shots to look up by their main file's fingerprint before sending (the index did not match them). */
        val lookups: List<Shot>,
    )

    /** [ordered] (Reveal's order: the files of a shot together, RAW first) grouped into shots, order kept. */
    fun shots(ordered: List<String>): List<Shot> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (p in ordered) {
            val name = p.substringAfterLast('/')
            val key = p.substringBeforeLast('/', "").lowercase() + "/" + photoStem(name).lowercase()
            groups.getOrPut(key) { ArrayList() } += p
        }
        return groups.map { (k, v) -> Shot(k, v) }
    }

    /**
     * The plan. Not [paired] or Reveal before 0.9.0 ([acceptsReferences] false): every path as a URI, as before.
     * Otherwise a shot with a file the index matched goes by reference (the RAW's item first); a shot looked up
     * ([looked]: shot key → item id, or null when the server does not hold it) goes by what the lookup said; a shot
     * with a file the index knows but whose size or mtime changed, or one it does not know, is looked up when
     * [lookupsAllowed] (a mapped folder, or a single shot) and fewer than [MAX_LOOKUPS] are planned; else URIs.
     */
    fun plan(
        ordered: List<String>,
        match: (String) -> FileMatch,
        acceptsReferences: Boolean,
        paired: Boolean,
        lookupsAllowed: Boolean,
        looked: Map<String, Pair<String, String?>?> = emptyMap(),
    ): Plan {
        if (!acceptsReferences || !paired) return Plan(emptyList(), ordered, emptyList())
        val server = ArrayList<ServerShot>()
        val uris = ArrayList<String>()
        val lookups = ArrayList<Shot>()
        for (shot in shots(ordered)) {
            val matches = shot.paths.map { it to match(it) }
            val mapped = matches.filter { it.second is FileMatch.Mapped }
            val best = mapped.firstOrNull { (it.second as FileMatch.Mapped).role == "raw" } ?: mapped.firstOrNull()
            when {
                best != null -> {
                    val m = best.second as FileMatch.Mapped
                    server += ServerShot(shot, m.itemId, best.first, m.fingerprint)
                }
                shot.key in looked -> {
                    val hit = looked[shot.key]
                    if (hit != null) server += ServerShot(shot, hit.first, shot.main, hit.second) else uris += shot.paths
                }
                lookupsAllowed && lookups.size < MAX_LOOKUPS -> lookups += shot
                else -> uris += shot.paths
            }
        }
        return Plan(server, uris, lookups)
    }
}
