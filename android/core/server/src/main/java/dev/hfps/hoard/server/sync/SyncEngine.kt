package dev.hfps.hoard.server.sync

import androidx.room.withTransaction
import dev.hfps.hoard.server.KeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.api.Candidate
import dev.hfps.hoard.server.api.DevicePatch
import dev.hfps.hoard.server.api.DeviceView
import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.LookupResponse
import dev.hfps.hoard.server.api.PairRequest
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.db.PendingCullingEntity
import dev.hfps.hoard.server.db.ServerAccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** Who this installation is, for pairing and `PATCH /v1/device` (ADR 0017). */
data class DeviceInfo(
    val name: String,
    /** `hoard` or `hoard-debug`. */
    val app: String,
    val manufacturer: String?,
    val model: String?,
    val appVersion: String,
)

/** What pairing found: the server, and the physical devices to offer ("Is this the same phone as…"). */
data class Paired(val serverName: String?, val candidates: List<Candidate>)

/** The engine's state for Settings › Photo server. */
data class SyncStatus(
    val running: Boolean = false,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val error: String? = null,
    /** Why the culling push stopped early (the sync itself went on), null when it did not. */
    val pushError: String? = null,
)

/** Why the server refused to pair (the UI says it in words). */
class PairingException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { InvalidCode, TokenRefused, OtherServer, Unreachable, TooOld, Insecure }
}

/**
 * The client side of hoard-server for the Gallery (ADR 0021): pairing and device identity (ADR 0013, 0017), the items
 * feed with culling into `gallery.db` (ADR 0014, 0016), and the culling queue pushed per field (ADR 0016 rules 1-4,
 * 6). One instance per process; [sync] is serialised.
 */
class SyncEngine(
    private val db: GalleryDatabase,
    private val vault: KeyVault,
    private val device: DeviceInfo,
    private val apiFor: (url: String, key: () -> String?) -> ServerApi = { url, key -> ServerApi(url, key, userAgent = "Hoard/${device.appVersion}") },
    private val now: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 500,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private val _compress = MutableStateFlow<dev.hfps.hoard.server.api.CompressInfo?>(null)

    /**
     * `GET /v1/server`'s `compress` object (ADR 0025) as of the last sync or [noteServerInfo]: which compression
     * targets are usable. Kept in memory (the next sync refreshes it); null before one, or when the server has none.
     */
    val compress: StateFlow<dev.hfps.hoard.server.api.CompressInfo?> = _compress

    /** A fresh `GET /v1/server` read elsewhere (the compression screens): its features and `compress` object. */
    suspend fun noteServerInfo(info: dev.hfps.hoard.server.api.ServerInfo) {
        _compress.value = info.compress
        db.account().setServerInfo(info.features.joinToString(","), info.serverName, info.version)
    }

    @Volatile private var cachedApi: ServerApi? = null

    /** The API of the paired server (null when not paired). */
    suspend fun api(): ServerApi? {
        val account = db.account().get() ?: return null
        cachedApi?.takeIf { it.baseUrl == account.url }?.let { return it }
        return apiFor(account.url, vault::get).also { cachedApi = it }
    }

    suspend fun account(): ServerAccountEntity? = db.account().get()

    // --- Pairing (ADR 0013 "Pairing", ADR 0017 "Client calls" 1) ---------------------------------------------------

    suspend fun pair(code: PairingCode): Paired = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!dev.hfps.hoard.server.ServerAddress.allowed(code.url)) throw PairingException(PairingException.Reason.Insecure)
            val api = apiFor(code.url) { null }
            val answer = try {
                api.pair(PairRequest(code.token, device.name, "android", device.appVersion, device.app, device.manufacturer?.take(64), device.model?.take(64)))
            } catch (e: ServerException) {
                throw PairingException(if (e.status == 401) PairingException.Reason.TokenRefused else PairingException.Reason.Unreachable, e)
            } catch (e: java.io.IOException) {
                throw PairingException(PairingException.Reason.Unreachable, e)
            }
            if (code.serverId != null && answer.serverId != code.serverId) throw PairingException(PairingException.Reason.OtherServer)
            // A new pairing replaces any older one: its cache belongs to another server or key.
            clearAll()
            vault.set(answer.apiKey)
            val keyed = apiFor(code.url, vault::get).also { cachedApi = it }
            val info = runCatching { keyed.server() }.getOrNull()
            if (info != null && Features.ITEMS !in info.features) {
                vault.clear()
                throw PairingException(PairingException.Reason.TooOld)
            }
            val models = answer.candidates.filter { it.match == "model" }
            db.account().put(
                ServerAccountEntity(
                    url = code.url,
                    serverId = answer.serverId,
                    serverName = info?.serverName ?: answer.serverName,
                    serverVersion = info?.version,
                    deviceId = answer.deviceId,
                    physicalDeviceId = answer.physicalDeviceId,
                    app = device.app,
                    features = info?.features.orEmpty().joinToString(","),
                    cursor = null,
                    pairedAt = now(),
                    claimCandidates = if (models.isEmpty()) null else ServerJson.encodeToString(ListSerializer(Candidate.serializer()), models),
                ),
            )
            Paired(info?.serverName ?: answer.serverName, models)
        }
    }

    /** The candidates to ask about once after pairing (ADR 0017 step 3), or empty. */
    suspend fun pendingClaim(): List<Candidate> {
        val json = db.account().get()?.claimCandidates ?: return emptyList()
        return runCatching { ServerJson.decodeFromString(ListSerializer(Candidate.serializer()), json) }.getOrDefault(emptyList())
    }

    /** "Same phone" (claim [physicalDeviceId]), "Another device" (null with [answerOnly]), or "its own" (null). */
    suspend fun claim(physicalDeviceId: String?, answerOnly: Boolean = false): DeviceView? = withContext(Dispatchers.IO) {
        db.account().setClaimCandidates(null)
        if (answerOnly) return@withContext null
        val view = api()?.claim(physicalDeviceId) ?: return@withContext null
        db.account().setPhysicalDevice(view.physicalDevice?.id)
        view
    }

    /** "This phone" in Settings (ADR 0017 "Client calls" 3), refreshing the stored physical device id. */
    suspend fun deviceView(): DeviceView? = withContext(Dispatchers.IO) {
        val account = db.account().get() ?: return@withContext null
        if (!account.has(Features.DEVICE_IDENTITY)) return@withContext null
        val view = api()?.device() ?: return@withContext null
        db.account().setPhysicalDevice(view.physicalDevice?.id)
        view
    }

    /**
     * Moves the pairing to another address of the same server (an `https://` one, e.g. `tailscale serve`): accepted
     * when [ServerAddress] allows it and `GET /v1/server` there answers with the same server id for this key.
     */
    suspend fun changeAddress(url: String): Boolean = withContext(Dispatchers.IO) {
        val clean = url.trim().trimEnd('/')
        if (!dev.hfps.hoard.server.ServerAddress.allowed(clean)) return@withContext false
        mutex.withLock {
            val account = db.account().get() ?: return@withLock false
            val info = runCatching { apiFor(clean, vault::get).server() }.getOrNull() ?: return@withLock false
            if (info.serverId != account.serverId) return@withLock false
            db.account().put(account.copy(url = clean, lastError = null))
            cachedApi = null
            true
        }
    }

    /** Forgets the server: the key, the cache and the queue for it (local decisions on device items stay). */
    suspend fun unpair() = mutex.withLock {
        _compress.value = null
        clearAll()
    }

    /** Drops the cached items and records; the next sync pulls from the start (the queue stays). */
    suspend fun clearCache() = mutex.withLock {
        db.withTransaction {
            db.items().clearItems()
            db.items().clearFiles()
            db.items().clearMirrors()
            db.culling().clearRecords()
            db.culling().clearFingerprints()
            db.account().setCursor(null, false)
        }
    }

    private suspend fun clearAll() {
        vault.clear()
        cachedApi = null
        db.withTransaction {
            db.items().clearItems()
            db.items().clearFiles()
            db.items().clearMirrors()
            db.culling().clearRecords()
            db.culling().clearFingerprints()
            db.culling().clearPending()
            db.device().clearPushed()
            // ADR 0025 A2: the jobs and the replaces of that server (a write already started keeps its row and part).
            db.compress().clearJobs()
            db.compress().clearUnstarted()
            // ADR 0022: mappings belong to the paired server.
            db.mappings().clearMappings()
            db.mappings().clearAllMisses()
            db.account().clear()
        }
    }

    // --- Culling queue (rule 2) ------------------------------------------------------------------------------------

    /** Queues a local decision on a server item (rule 2): one pending change per field that changed. */
    suspend fun decide(itemId: String, before: CullValues, after: CullValues, at: Long = now()) {
        db.withTransaction {
            val record = db.culling().get(itemId)
            val rows = CullingRules.queue(itemId, record, before, after, at)
            if (rows.isNotEmpty()) db.culling().putPending(rows)
        }
    }

    /**
     * A phone-only shot reached the server (ADR 0021): its local decision is queued with `baseRev` 0 (the phone never
     * saw the server's fields), so the server keeps whichever of the two was made last (ADR 0016 step 5).
     */
    suspend fun promote(itemId: String, values: CullValues, at: Long, clears: Boolean = false) {
        db.withTransaction {
            val record = db.culling().get(itemId)
            val server = CullingRules.serverValues(record)
            val rows = CullingRules.queue(itemId, record, server, server.copy(rating = values.rating, flag = values.flag), at)
                // A first hand-over sends only what was decided; a later one (decided again) also sends what was cleared.
                .filter { clears || (it.field == CullField.Rating.wire && values.rating > 0) || (it.field == CullField.Flag.wire && values.flag != null) }
                .map { it.copy(baseRev = 0) }
            if (rows.isNotEmpty()) db.culling().putPending(rows)
        }
    }

    /**
     * ADR 0022 "Culling: dual write": a decision on a mapped SMB shot was also written into its XMP sidecar, whose
     * mtime is [sidecarMtime]. The changes still pending for [itemId] that carry exactly these [values] take that time,
     * so the server's later import of the same sidecar finds the same value at the same time (a no-op, no revision).
     */
    suspend fun redate(itemId: String, values: CullValues, sidecarMtime: Long) {
        db.withTransaction {
            val rows = CullingRules.redated(db.culling().pendingFor(itemId), values, sidecarMtime)
            if (rows.isNotEmpty()) db.culling().putPending(rows)
        }
    }

    // --- Sync ------------------------------------------------------------------------------------------------------

    /**
     * Push, pull, push: what the background job and "Sync now" run. False when not paired or when the pull failed. A
     * refused push never stops the pull (the refused changes are marked and backed off, [push]).
     */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account = preferHttps(db.account().get() ?: return@withContext false)
            val api = api() ?: return@withContext false
            _status.value = SyncStatus(running = true)
            try {
                val info = api.server()
                db.account().setServerInfo(info.features.joinToString(","), info.serverName, info.version)
                _compress.value = info.compress
                val culling = Features.CULLING in info.features
                // Rule 6: `culling` is declared before the first culling push (a push without it would let the server
                // bridge records again); a failed declaration is retried on the next sync, and nothing is pushed until then.
                val canPush = culling && (account.cullingDeclared || declareCulling(api, info.features))
                val first = if (canPush) push(api, account.deviceId) else PushPass()
                indexMirrors()
                val pulled = pull(api, minOf(pageSize, info.limits.maxPullLimit))
                if (Features.BACKUP in info.features) refreshBackupRoots(api) else db.account().setBackupRoots(null)
                val second = if (canPush) push(api, account.deviceId) else PushPass()
                db.account().synced(now())
                _status.value = SyncStatus(running = false, pulled = pulled, pushed = first.answered + second.answered, pushError = second.error ?: first.error)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Replaced by a newer sync (WorkManager) or the app left: not a failure.
                _status.value = _status.value.copy(running = false)
                throw e
            } catch (e: Throwable) {
                val message = when {
                    e is ServerException && e.unauthorized -> ERROR_UNAUTHORIZED
                    e is ServerException -> e.code
                    else -> ERROR_OFFLINE
                }
                runCatching { android.util.Log.w("HoardServer", "sync failed: $message", e) }
                db.account().failed(message, now())
                _status.value = SyncStatus(running = false, error = message)
                false
            }
        }
    }

    /**
     * Every phone's backup roots (`GET /v1/backup/roots`, ADR 0024 A2), kept as JSON for Gallery › Devices. A failure
     * keeps the last list (the tree is built from the items' mirror locations too).
     */
    private suspend fun refreshBackupRoots(api: ServerApi) {
        val roots = runCatching { api.backupRoots() }.getOrElse {
            if (it is ServerException && it.unauthorized) throw it
            runCatching { android.util.Log.w("HoardServer", "backup roots: ${it.message}") }
            return
        }
        db.account().setBackupRoots(ServerJson.encodeToString(dev.hfps.hoard.server.api.BackupRoots.serializer(), roots))
    }

    /** gallery.db 5: the `mirror` locations of items cached before the upgrade, read once from their JSON. */
    private suspend fun indexMirrors() {
        if (db.account().get()?.mirrorsIndexed != false) return
        var offset = 0
        while (true) {
            val rows = db.items().mirrorJson(MIRROR_PAGE, offset)
            if (rows.isEmpty()) break
            offset += rows.size
            val mirrors = rows.flatMap { row ->
                runCatching { Feed.mirrors(ServerJson.decodeFromString(dev.hfps.hoard.server.api.Item.serializer(), row.json)) }.getOrDefault(emptyList())
            }
            db.withTransaction {
                db.items().deleteMirrors(rows.map { it.id })
                if (mirrors.isNotEmpty()) db.items().putMirrors(mirrors)
            }
        }
        db.account().setMirrorsIndexed(true)
    }

    @Volatile private var httpsTriedAt = Long.MIN_VALUE / 2

    /**
     * Prefers `https://` (ADR 0021 "Plain HTTP"): a server paired by its plain-HTTP MagicDNS name (`*.ts.net`) is
     * asked, at most once a day, whether it answers on `https://<name>` (`tailscale serve`) with the same server id for
     * this key; then the pairing moves there. Any failure keeps the address as it is.
     */
    private suspend fun preferHttps(account: ServerAccountEntity): ServerAccountEntity {
        val uri = runCatching { java.net.URI(account.url) }.getOrNull() ?: return account
        val host = uri.host?.lowercase() ?: return account
        if (uri.scheme?.lowercase() != "http" || !host.endsWith(".ts.net") || now() - httpsTriedAt < HTTPS_RETRY_MS) return account
        httpsTriedAt = now()
        val https = "https://$host"
        val info = runCatching { apiFor(https, vault::get).server() }.getOrNull() ?: return account
        if (info.serverId != account.serverId) return account
        val moved = account.copy(url = https)
        db.account().put(moved)
        cachedApi = null
        runCatching { android.util.Log.i("HoardServer", "the server answers on https://$host: moved the pairing there") }
        return moved
    }

    /** ADR 0016 rule 6 / ADR 0017 "Client calls" 2: Hoard declares `culling` (with its app id and model) once. */
    private suspend fun declareCulling(api: ServerApi, features: List<String>): Boolean {
        val patch = DevicePatch(
            app = device.app.takeIf { Features.DEVICE_IDENTITY in features },
            manufacturer = device.manufacturer?.take(64)?.takeIf { Features.DEVICE_IDENTITY in features },
            model = device.model?.take(64)?.takeIf { Features.DEVICE_IDENTITY in features },
            features = listOf(Features.CULLING),
        )
        return try {
            api.patchDevice(patch)
            db.account().cullingDeclared()
            true
        } catch (e: ServerException) {
            if (e.unauthorized) throw e
            runCatching { android.util.Log.w("HoardServer", "declaring culling failed: ${e.code}; no culling push until it succeeds") }
            false
        } catch (e: java.io.IOException) {
            runCatching { android.util.Log.w("HoardServer", "declaring culling failed (offline); no culling push until it succeeds") }
            false
        }
    }

    /** What one push pass did: changes answered, and why it stopped early (null when it did not). */
    data class PushPass(val answered: Int = 0, val error: String? = null)

    /** When a refused change may be sent again (`itemId/field` → time); a new decision on the field resets it. */
    private val retryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun due(p: PendingCullingEntity): Boolean = p.attempts == 0 || (retryAt["${p.itemId}/${p.field}"] ?: 0L) <= now()

    /** Marks changes the server did not take (attempts + 1) and keeps them back for a while (30 s doubling, ≤ 6 h). */
    private suspend fun refused(rows: List<PendingCullingEntity>) {
        for (p in rows) {
            db.culling().attempted(p.itemId, p.field)
            retryAt["${p.itemId}/${p.field}"] = now() + backoffMs(p.attempts + 1)
        }
    }

    /**
     * Pushes every due change of the queue once, in batches. A batch the server refuses (an error answer, or no
     * connection) is marked and backed off, and the pass stops there; a change the answer leaves out is marked too, so
     * no batch is ever sent twice in a pass. Only a revoked key ends the sync (the pull would be refused as well).
     */
    private suspend fun push(api: ServerApi, deviceId: String): PushPass {
        val due = db.culling().pendingAll().filter(::due)
        var answered = 0
        for (batch in due.chunked(MAX_PUSH)) {
            val response = try {
                api.pushCulling(CullingRules.changes(batch))
            } catch (e: ServerException) {
                if (e.unauthorized) throw e
                refused(batch)
                runCatching { android.util.Log.w("HoardServer", "culling push refused: ${e.status} ${e.code}; ${batch.size} changes backed off") }
                return PushPass(answered, e.code)
            } catch (e: java.io.IOException) {
                refused(batch)
                runCatching { android.util.Log.w("HoardServer", "culling push failed: ${e.message}; ${batch.size} changes backed off") }
                return PushPass(answered, ERROR_OFFLINE)
            }
            val byIndex = response.results.associateBy { it.index }
            val unanswered = ArrayList<PendingCullingEntity>()
            db.withTransaction {
                batch.forEachIndexed { i, sent ->
                    val result = byIndex[i]
                    if (result == null) {
                        unanswered += sent
                        return@forEachIndexed
                    }
                    when (val outcome = CullingRules.outcome(sent, result)) {
                        is CullingRules.Outcome.Done -> {
                            db.culling().acknowledge(sent.itemId, sent.field, sent.modifiedAt, sent.value)
                            if (outcome.wrote && outcome.rev != null) {
                                val record = db.culling().get(outcome.itemId)
                                db.culling().put(listOf(CullingRules.written(record, outcome.itemId, sent, outcome.rev, deviceId)))
                            }
                        }
                        is CullingRules.Outcome.Dropped -> db.culling().acknowledge(sent.itemId, sent.field, sent.modifiedAt, sent.value)
                    }
                }
            }
            if (unanswered.isNotEmpty()) {
                refused(unanswered)
                runCatching { android.util.Log.w("HoardServer", "culling push: ${unanswered.size} of ${batch.size} changes not answered; backed off") }
            }
            answered += batch.size - unanswered.size
        }
        return PushPass(answered)
    }

    /**
     * Pulls the items feed (with culling) to its end; returns how many items and records were applied. A page that
     * says `hasMore` without moving the cursor ends the pass (logged; the next sync starts from the last cursor).
     */
    private suspend fun pull(api: ServerApi, limit: Int): Int {
        var applied = 0
        var restarted = false
        var emptyPages = 0
        while (true) {
            val account = db.account().get() ?: return applied
            val page = try {
                api.itemsChanges(account.cursor, limit, if (account.has(Features.TRASH)) dev.hfps.hoard.server.api.INCLUDE_ITEMS_CULLING_TRASH else dev.hfps.hoard.server.api.INCLUDE_ITEMS_CULLING)
            } catch (e: ServerException) {
                if (e.code == "invalid_cursor" && !restarted) {
                    restarted = true
                    db.account().setCursor(null, false)
                    continue
                }
                throw e
            }
            val writes = Feed.writes(page)
            db.withTransaction {
                db.items().apply(writes.items, writes.files, writes.goneItems, writes.mirrors)
                db.culling().apply(writes.records, writes.recordFingerprints, writes.replaced.keys.toList())
                for ((from, to) in writes.replaced) db.culling().movePending(from, to)
                db.account().setCursor(page.cursor ?: account.cursor, account.caughtUp || !page.hasMore)
            }
            val n = page.items.size + page.culling.size
            applied += n
            _status.value = _status.value.copy(pulled = applied)
            if (!page.hasMore) return applied
            emptyPages = if (n == 0) emptyPages + 1 else 0
            if (page.cursor == null || page.cursor == account.cursor || emptyPages >= MAX_EMPTY_PAGES) {
                runCatching { android.util.Log.w("HoardServer", "items feed made no progress (cursor ${if (page.cursor == account.cursor) "unchanged" else "missing"}, $emptyPages empty pages); pass stopped") }
                return applied
            }
        }
    }

    /** `POST /v1/items/lookup` in chunks (ADR 0014; the fallback before the first full pull). */
    suspend fun lookup(fingerprints: List<String>): LookupResponse? = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext null
        val all = fingerprints.distinct()
        val items = ArrayList<dev.hfps.hoard.server.api.Item>()
        val matches = ArrayList<dev.hfps.hoard.server.api.LookupMatch>()
        val missing = ArrayList<String>()
        for (chunk in all.chunked(1000)) {
            val r = api.lookup(chunk)
            items += r.items
            matches += r.matches
            missing += r.missing
        }
        LookupResponse(items.distinctBy { it.id }, matches, missing)
    }

    suspend fun pending(itemId: String): List<PendingCullingEntity> = db.culling().pendingFor(itemId)

    companion object {
        const val MAX_PUSH = 500
        /** Pages in a row with `hasMore` and nothing in them before a pull pass gives up. */
        const val MAX_EMPTY_PAGES = 50
        const val HTTPS_RETRY_MS = 24 * 3_600_000L

        /** How long a change refused [attempts] times is kept back: 30 s, doubling, at most 6 hours. */
        const val MIRROR_PAGE = 200

        fun backoffMs(attempts: Int): Long = minOf(30_000L shl (attempts - 1).coerceIn(0, 20), 6 * 3_600_000L)
        const val ERROR_UNAUTHORIZED = "unauthorized"
        const val ERROR_OFFLINE = "offline"
    }
}
