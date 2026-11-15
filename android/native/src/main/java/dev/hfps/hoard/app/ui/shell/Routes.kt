package dev.hfps.hoard.app.ui.shell

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (ADR 0019). Tabs first, then full-screen destinations. */
@Serializable data object GalleryRoute

@Serializable data object FilesRoute

@Serializable data object SettingsRoute

@Serializable data object LicencesRoute

/** A folder (or a machine's share list: `share` null) of any location (ADR 0005): part of the Files tab. */
@Serializable data class BrowseRoute(val locationId: String, val share: String? = null, val path: String = "")

/** New machine (`machineId` null) or edit one. */
@Serializable data class MachineFormRoute(val machineId: String? = null)

@Serializable data object TransfersRoute

/** "Save to Hoard": files another app shared (ADR 0007). */
@Serializable data object SaveRoute

/** First run: all files access (ADR 0005). */
@Serializable data object StorageAccessRoute

@Serializable data class ImageViewerRoute(val locationId: String, val share: String? = null, val path: String)

@Serializable data class TextViewerRoute(val locationId: String, val share: String? = null, val path: String, val edit: Boolean = false)

/** A folder of any location opened for culling (ADR 0008). */
@Serializable data class CullRoute(val locationId: String, val share: String? = null, val path: String = "")

/** Settings › Photo server › Pair (ADR 0021). */
@Serializable data object PairRoute
