package dev.hfps.hoard

import android.content.res.Configuration
import android.graphics.Color
import androidx.core.view.WindowCompat
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Keeps the status and navigation bars in step with the app's own theme (`src/plugins/systemUi.ts`).
 *
 * Capacitor's SystemBars paints the strips behind the bars with the activity's window background
 * and picks the icon color from the *system* night mode. The app has its own light/dark choice, so
 * with "Dark" on a light phone the bars stayed white. The web side reports the resolved theme and
 * its background color; this paints them and keeps doing so after configuration changes (where
 * SystemBars resets the decor background).
 */
@CapacitorPlugin(name = "SystemUi")
class SystemUiPlugin : Plugin() {

    private var dark: Boolean? = null
    private var background: Int? = null

    @PluginMethod
    fun setTheme(call: PluginCall) {
        val isDark = call.getBoolean("dark") ?: return call.reject("dark is required")
        val color = call.getString("background")?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        dark = isDark
        background = color
        bridge.executeOnMainThread {
            apply()
            call.resolve()
        }
    }

    override fun handleOnConfigurationChanged(newConfig: Configuration?) {
        super.handleOnConfigurationChanged(newConfig)
        // Run after SystemBars' own handler, which repaints with the system theme.
        bridge.webView?.post { apply() }
    }

    override fun handleOnResume() {
        super.handleOnResume()
        bridge.webView?.post { apply() }
    }

    private fun apply() {
        val isDark = dark ?: return
        val window = activity?.window ?: return
        background?.let { window.decorView.setBackgroundColor(it) }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
