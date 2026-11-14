package dev.hfps.hoard.app

import android.content.Context
import dev.hfps.hoard.app.ui.files.VolumesSource
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.data.legacy.CapacitorImporter

/**
 * The app's object graph, built by hand (ADR 0019: a handful of singletons does not need Hilt yet). One per process,
 * in [HoardApplication]; screens reach it through their ViewModel factories.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: HoardDatabase by lazy { HoardDatabase.open(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(database.settings()) }
    val importer: CapacitorImporter by lazy { CapacitorImporter(appContext, database) }
    val volumes: VolumesSource by lazy { VolumesSource(appContext) }
}
