package dev.hfps.hoard.app.data

import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.transfers.CONFLICT_POLICIES
import dev.hfps.hoard.app.transfers.RecentDestination
import dev.hfps.hoard.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Files side's settings, the keys and JSON values Hoard 1 writes (src/features/settings/settings.ts): a value that
 * fails validation reads as the default, exactly as there.
 */
data class FilesSettings(
    val showHiddenFiles: Boolean = false,
    val defaultSort: SortOrder = SortOrder.DEFAULT,
    /** `ask`, `rename`, `overwrite` or `skip`. */
    val defaultConflict: String = "ask",
    val recentDestinations: List<RecentDestination> = emptyList(),
    val probeTimeoutMs: Long = 1500,
    /** The all files access screen was shown once (granted or "Not now"); ADR 0005. */
    val storageIntroSeen: Boolean = false,
    /** "Edit in Reveal" on SMB photos stopped explaining that they stream through Hoard (ADR 0010). */
    val revealStreamNoteSeen: Boolean = false,
    /** Culling writes sidecars (ADR 0011); Reveal gets writable sidecars only when on. */
    val cullSaveXmp: Boolean = true,
    val cullXmpHidden: Boolean = false,
)

class AppSettings(private val store: SettingsStore) {

    val files: Flow<FilesSettings> = combine(
        listOf(
            store.observeJson(SHOW_HIDDEN),
            store.observeJson(DEFAULT_SORT),
            store.observeJson(DEFAULT_CONFLICT),
            store.observeJson(RECENT_DESTINATIONS),
            store.observeJson(PROBE_TIMEOUT),
            store.observeJson(STORAGE_INTRO_SEEN),
            store.observeJson(REVEAL_STREAM_NOTE_SEEN),
            store.observeJson(CULL_SAVE_XMP),
            store.observeJson(CULL_XMP_HIDDEN),
        ),
    ) { values -> parse(values.toList()) }

    val probeTimeoutMs: Flow<Long> = files.map { it.probeTimeoutMs }

    suspend fun setShowHidden(value: Boolean) = store.putJson(SHOW_HIDDEN, value.toString())
    suspend fun setDefaultSort(value: SortOrder) = store.putJson(DEFAULT_SORT, value.toJson())
    suspend fun setDefaultConflict(value: String) {
        require(value in CONFLICT_POLICIES)
        store.putJson(DEFAULT_CONFLICT, JSONArray().put(value).toString().removeSurrounding("[", "]"))
    }
    suspend fun setRecentDestinations(list: List<RecentDestination>) = store.putJson(RECENT_DESTINATIONS, RecentDestination.listToJson(list))
    suspend fun setProbeTimeout(ms: Long) {
        require(ms in 200..30_000)
        store.putJson(PROBE_TIMEOUT, ms.toString())
    }
    suspend fun setStorageIntroSeen(value: Boolean) = store.putJson(STORAGE_INTRO_SEEN, value.toString())
    suspend fun setRevealStreamNoteSeen(value: Boolean) = store.putJson(REVEAL_STREAM_NOTE_SEEN, value.toString())

    companion object {
        const val SHOW_HIDDEN = "showHiddenFiles"
        const val DEFAULT_SORT = "defaultSort"
        const val DEFAULT_CONFLICT = "defaultConflict"
        const val RECENT_DESTINATIONS = "recentDestinations"
        const val PROBE_TIMEOUT = "probeTimeoutMs"
        const val STORAGE_INTRO_SEEN = "storageIntroSeen"
        const val REVEAL_STREAM_NOTE_SEEN = "revealStreamNoteSeen"
        const val CULL_SAVE_XMP = "cullSaveXmp"
        const val CULL_XMP_HIDDEN = "cullXmpHidden"

        /** The probe timeouts Settings offers (ms). */
        val PROBE_TIMEOUTS = listOf(800L, 1500L, 3000L, 5000L)

        /** Pure: the settings from their stored JSON texts, in the order of [files]'s keys (JVM-tested). */
        fun parse(values: List<String?>): FilesSettings {
            val d = FilesSettings()
            return FilesSettings(
                showHiddenFiles = bool(values[0]) ?: d.showHiddenFiles,
                defaultSort = sort(values[1]) ?: d.defaultSort,
                defaultConflict = string(values[2])?.takeIf { it in CONFLICT_POLICIES } ?: d.defaultConflict,
                recentDestinations = RecentDestination.listFromJson(values[3]),
                probeTimeoutMs = number(values[4])?.takeIf { it in 200.0..30_000.0 }?.toLong() ?: d.probeTimeoutMs,
                storageIntroSeen = bool(values[5]) ?: d.storageIntroSeen,
                revealStreamNoteSeen = bool(values[6]) ?: d.revealStreamNoteSeen,
                cullSaveXmp = bool(values[7]) ?: d.cullSaveXmp,
                cullXmpHidden = bool(values[8]) ?: d.cullXmpHidden,
            )
        }

        private fun value(json: String?): Any? = json?.let { runCatching { JSONArray("[$it]").opt(0) }.getOrNull() }
        private fun bool(json: String?) = value(json) as? Boolean
        private fun string(json: String?) = value(json) as? String
        private fun number(json: String?) = (value(json) as? Number)?.toDouble()

        fun sort(json: String?): SortOrder? {
            val o = value(json) as? JSONObject ?: return null
            val key = SortKey.entries.firstOrNull { it.id == o.optString("key") } ?: return null
            val direction = o.optString("direction")
            if (direction != "asc" && direction != "desc") return null
            return SortOrder(key, direction == "asc")
        }
    }
}
