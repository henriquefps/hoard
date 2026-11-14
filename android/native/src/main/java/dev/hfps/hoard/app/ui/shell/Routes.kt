package dev.hfps.hoard.app.ui.shell

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (ADR 0019). Tabs first, then full-screen destinations. */
@Serializable data object GalleryRoute

@Serializable data object FilesRoute

@Serializable data object SettingsRoute

@Serializable data object LicencesRoute
