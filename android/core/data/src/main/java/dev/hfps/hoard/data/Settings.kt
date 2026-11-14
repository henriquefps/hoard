package dev.hfps.hoard.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

/**
 * Settings live in the `settings` table as JSON values, the Capacitor app's format (ADR 0003), so imported keys keep
 * working. The native app adds per-device appearance keys that the Capacitor app kept in the WebView's localStorage
 * (not imported): [THEME_MODE] (`system` / `light` / `dark`) and [PALETTE] (`blue`, `olive`, `violet`, `rose`,
 * `graphite`).
 */
class SettingsStore(private val dao: SettingsDao) {
    fun observeString(key: String): Flow<String?> = dao.observe(key).map { it?.let(::decodeString) }

    suspend fun getString(key: String): String? = dao.get(key)?.let(::decodeString)

    suspend fun putString(key: String, value: String) = dao.put(SettingEntity(key, encodeString(value)))

    /** The stored JSON text of [key] (any JSON value), null when unset. Typed reading is the caller's. */
    fun observeJson(key: String): Flow<String?> = dao.observe(key)

    suspend fun getJson(key: String): String? = dao.get(key)

    /** Stores [json] (already JSON text: `true`, `1500`, `{"key":"name","direction":"asc"}`) as Hoard 1 does. */
    suspend fun putJson(key: String, json: String) = dao.put(SettingEntity(key, json))

    companion object {
        const val THEME_MODE = "themeMode"
        const val PALETTE = "palette"

        /** A JSON string value (`"dark"`); null when the stored value is not a JSON string. */
        fun decodeString(json: String): String? = try {
            JSONArray("[$json]").opt(0) as? String
        } catch (_: JSONException) {
            null
        }

        fun encodeString(value: String): String = JSONArray().put(value).toString().removeSurrounding("[", "]")
    }
}
