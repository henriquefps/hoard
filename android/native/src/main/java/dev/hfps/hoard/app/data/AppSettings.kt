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
    /** Hoard 2.1 and older: culling wrote sidecars (ADR 0011). Forced off in 2.2.0 ([AppSettings.forceNoAutomaticXmp]). */
    val cullSaveXmp: Boolean = false,
    val cullXmpHidden: Boolean = false,
)

/** Culling's settings (settings.ts): the viewer, assisted culling (ADR 0009) and the sidecars (ADR 0011). */
data class CullSettings(
    val autoAdvance: Boolean = true,
    val swipeDecide: Boolean = false,
    val blur: dev.hfps.hoard.app.cull.Sensitivity = dev.hfps.hoard.app.cull.Sensitivity.Medium,
    val eyes: dev.hfps.hoard.app.cull.Sensitivity = dev.hfps.hoard.app.cull.Sensitivity.Medium,
    val burstGapMs: Long = 1000,
    /** Always off since 2.2.0: nothing writes sidecars automatically ("Export XMP" does, ADR 0011 amendment). */
    val saveXmp: Boolean = false,
    /** "Export XMP" names new sidecars `.<stem>.xmp`. */
    val xmpHidden: Boolean = false,
) {
    val assist: dev.hfps.hoard.app.cull.AssistSettings get() = dev.hfps.hoard.app.cull.AssistSettings(blur, eyes, burstGapMs)
}

class AppSettings(private val store: SettingsStore) {

    val cull: Flow<CullSettings> = combine(
        listOf(
            store.observeJson(CULL_AUTO_ADVANCE),
            store.observeJson(CULL_SWIPE_DECIDE),
            store.observeJson(CULL_BLUR),
            store.observeJson(CULL_EYES),
            store.observeJson(CULL_BURST_GAP),
            store.observeJson(CULL_SAVE_XMP),
            store.observeJson(CULL_XMP_HIDDEN),
        ),
    ) { values -> parseCull(values.toList()) }

    suspend fun setCullAutoAdvance(value: Boolean) = store.putJson(CULL_AUTO_ADVANCE, value.toString())
    suspend fun setCullSwipeDecide(value: Boolean) = store.putJson(CULL_SWIPE_DECIDE, value.toString())
    suspend fun setCullBlur(value: dev.hfps.hoard.app.cull.Sensitivity) = store.putJson(CULL_BLUR, JSONObject.quote(value.id))
    suspend fun setCullEyes(value: dev.hfps.hoard.app.cull.Sensitivity) = store.putJson(CULL_EYES, JSONObject.quote(value.id))
    suspend fun setCullBurstGap(ms: Long) {
        require(ms in 100..10_000)
        store.putJson(CULL_BURST_GAP, ms.toString())
    }
    /**
     * 2.2.0 (ADR 0011 amendment "export on demand"): the automatic sidecar setting is off for everyone, whatever Hoard 1
     * or 2.1 stored (the Room migration 1 → 2 does it for an upgraded database; this, at every start, also covers
     * settings imported from Hoard 1 afterwards). True when it had to change the stored value.
     */
    suspend fun forceNoAutomaticXmp(): Boolean {
        if (store.getJson(CULL_SAVE_XMP)?.trim() == "false") return false
        store.putJson(CULL_SAVE_XMP, "false")
        return true
    }
    suspend fun setCullXmpHidden(value: Boolean) = store.putJson(CULL_XMP_HIDDEN, value.toString())

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
    /**
     * Settings › Trash › "Empty the trash automatically" (ADR 0023): days after which a day folder of the trash is
     * deleted, one of [TRASH_AUTO_EMPTY_DAYS]; 0 = off. On by default, after 30 days (the owner's decision).
     */
    val trashAutoEmptyDays: Flow<Int> = store.observeJson(TRASH_AUTO_EMPTY).map(::parseTrashDays)

    suspend fun trashAutoEmptyDaysNow(): Int = parseTrashDays(store.getJson(TRASH_AUTO_EMPTY))

    suspend fun setTrashAutoEmptyDays(days: Int) {
        require(days in TRASH_AUTO_EMPTY_DAYS)
        store.putJson(TRASH_AUTO_EMPTY, days.toString())
    }

    /** The first phone trash dialog offered Media management (asked once, ADR 0023 owner's decision 4). */
    suspend fun trashMediaAskedNow(): Boolean = bool(store.getJson(TRASH_MEDIA_ASKED)) == true

    suspend fun setTrashMediaAsked() = store.putJson(TRASH_MEDIA_ASKED, "true")

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
        const val CULL_AUTO_ADVANCE = "cullAutoAdvance"
        const val CULL_SWIPE_DECIDE = "cullSwipeDecide"
        const val CULL_BLUR = "cullBlurSensitivity"
        const val CULL_EYES = "cullEyesSensitivity"
        const val CULL_BURST_GAP = "cullBurstGapMs"
        const val TRASH_AUTO_EMPTY = "trashAutoEmptyDays"
        const val TRASH_MEDIA_ASKED = "trashMediaManagementAsked"

        /** The auto-empty choices of Settings › Trash: 7, 30 or 90 days, or 0 = off (ADR 0023). */
        val TRASH_AUTO_EMPTY_DAYS = listOf(7, 30, 90, 0)
        const val TRASH_AUTO_EMPTY_DEFAULT = 30

        /** Pure: the stored auto-empty setting, the default (30) for anything that is not one of the choices. */
        fun parseTrashDays(json: String?): Int =
            number(json)?.takeIf { it % 1.0 == 0.0 }?.toInt()?.takeIf { it in TRASH_AUTO_EMPTY_DAYS } ?: TRASH_AUTO_EMPTY_DEFAULT

        /** Pure: the culling settings from their stored JSON texts, in the order of [cull]'s keys (JVM-tested). */
        fun parseCull(values: List<String?>): CullSettings {
            val d = CullSettings()
            return CullSettings(
                autoAdvance = bool(values[0]) ?: d.autoAdvance,
                swipeDecide = bool(values[1]) ?: d.swipeDecide,
                blur = dev.hfps.hoard.app.cull.Sensitivity.of(string(values[2])) ?: d.blur,
                eyes = dev.hfps.hoard.app.cull.Sensitivity.of(string(values[3])) ?: d.eyes,
                burstGapMs = number(values[4])?.takeIf { it in 100.0..10_000.0 }?.toLong() ?: d.burstGapMs,
                saveXmp = bool(values[5]) ?: d.saveXmp,
                xmpHidden = bool(values[6]) ?: d.xmpHidden,
            )
        }

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
