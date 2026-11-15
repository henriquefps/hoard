package dev.hfps.hoard.app.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The in-app language (Hoard 1's Settings › Language): English or Português (Brasil), or the system's. Android 13+ keeps
 * it as the app's per-app locale (`LocaleManager`, the same choice as the system's per-app language page, listed from
 * the generated locale config); before 13 it lives in a small preferences file and [wrap] applies it to the activity.
 * Hoard 1 kept it in the WebView's localStorage; its last release mirrors it into the `language` setting, which
 * [applyImported] applies once after the import (ADR 0019).
 */
object AppLanguage {
    /** The languages Hoard ships, as BCP 47 tags (Hoard 1's `LANGUAGES`). */
    val TAGS = listOf("en", "pt-BR")

    private const val PREFS = "hoard_language"
    private const val KEY = "tag"
    private const val IMPORTED = "imported"

    /** The chosen tag, or null for the system's language. */
    fun current(context: Context): String? = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()?.let(::normalise)
    } else {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }

    /** Hoard 1's tags; any Portuguese is pt-BR, anything else English. */
    fun normalise(tag: String?): String? = when {
        tag.isNullOrBlank() -> null
        tag.lowercase().startsWith("pt") -> "pt-BR"
        else -> "en"
    }

    /**
     * Sets the language (null = the system's). Android 13+ applies it to the running activities itself; before that
     * the activity is recreated so [wrap] picks it up.
     */
    fun set(context: Context, tag: String?) {
        val value = normalise(tag)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (value == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(value)
        } else {
            (context as? Activity)?.recreate()
        }
    }

    /** Before Android 13: the activity's context in the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /**
     * Once, after Hoard 1's database was imported: its language (the `language` setting its last release mirrors)
     * becomes Hoard 2's, unless a language was already chosen here. Returns whether it changed the language.
     */
    fun applyImported(context: Context, imported: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(IMPORTED, false)) return false
        prefs.edit().putBoolean(IMPORTED, true).apply()
        val tag = normalise(imported) ?: return false
        if (current(context) != null) return false
        set(context, tag)
        return true
    }
}
