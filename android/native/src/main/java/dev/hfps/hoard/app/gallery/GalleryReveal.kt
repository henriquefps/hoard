package dev.hfps.hoard.app.gallery

import android.app.Activity
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.share.RevealHandoff
import dev.hfps.hoard.smb.smbFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Edit in Reveal" from the Gallery (ADR 0010 "Server items", ADR 0021): a shot the paired server holds goes to Reveal
 * by **reference** (Reveal ADR 0011 amendment W3/H: server id + item id + fingerprints, no URI, no grant, no culling
 * keys; Reveal reads it from the server itself), when Reveal takes references (0.9.0+). Otherwise a shot on the phone
 * goes as today (URIs, grants, `CULLING`); a server-only shot cannot go to an older Reveal.
 */
object GalleryReveal {
    enum class Route { Server, Uri, Unavailable }

    /** How one shot goes to Reveal (pure, tested). */
    fun route(entry: GalleryEntry, paired: Boolean, acceptsReferences: Boolean): Route = when {
        entry.itemId != null && paired && acceptsReferences -> Route.Server
        entry.device != null -> Route.Uri
        else -> Route.Unavailable
    }

    /** The reference of a server item: the main file's fingerprint, every member's, the main file's name. */
    fun reference(item: Item): RevealHandoff.ServerItem {
        val main = item.files.firstOrNull { it.fingerprint == item.mainFingerprint }
        return RevealHandoff.ServerItem(
            itemId = item.id,
            fingerprint = item.mainFingerprint,
            fingerprints = item.files.filter { it.role != "sidecar" }.map { it.fingerprint },
            name = main?.name ?: item.files.firstOrNull()?.name.orEmpty(),
        )
    }

    /**
     * Sends [entries] (all of them [Route.Server]) by reference, after pushing their pending decisions (best effort,
     * a short wait): Reveal reads culling from the server.
     */
    suspend fun sendServer(repo: GalleryRepository, reveal: RevealHandoff, activity: Activity, entries: List<GalleryEntry>, title: String?, initial: GalleryEntry?) {
        val account = repo.db.account().get() ?: smbFail(ErrorCode.NOT_FOUND, "Not paired")
        val items = withContext(Dispatchers.IO) {
            entries.mapNotNull { e -> e.itemId?.let { repo.db.items().json(it) } }
                .map { reference(ServerJson.decodeFromString(Item.serializer(), it)) }
        }
        if (items.isEmpty()) smbFail(ErrorCode.NOT_FOUND, "Not on the server")
        val ids = items.map { it.itemId }.toSet()
        if (repo.db.culling().pending(10_000).any { it.itemId in ids }) withTimeoutOrNull(FLUSH_TIMEOUT_MS) { repo.syncNow() }
        val prepared = withContext(Dispatchers.IO) { reveal.prepare(emptyList(), title, null, emptyMap(), RevealHandoff.ServerItems(account.serverId, items, initial?.itemId)) }
        withContext(Dispatchers.Main) { reveal.start(activity, prepared) }
    }

    const val FLUSH_TIMEOUT_MS = 3000L
}
