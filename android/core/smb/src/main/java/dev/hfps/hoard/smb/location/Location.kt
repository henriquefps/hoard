package dev.hfps.hoard.smb.location

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.smbFail
import org.json.JSONObject

/**
 * Contract `LocationRef` (ADR 0005): where a file operation happens. Paths inside a location are
 * `/`-separated, without a leading slash, relative to its root.
 */
sealed class Location {
    /** A share on a connected session (the UI's route engine owns the session). */
    data class Smb(val sessionId: String, val share: String) : Location()

    /** Local storage: the phone's shared storage or a removable volume. */
    sealed class Local : Location()

    /** The phone's shared storage (`/storage/emulated/0`), the primary volume. */
    data object Device : Local()

    /** A removable volume (USB OTG drive, SD card) by its filesystem UUID. */
    data class Volume(val uuid: String) : Local()

    companion object {
        /** Parses the `location` object of a call. Rejects `UNKNOWN` for anything malformed. */
        fun parse(json: JSONObject?): Location {
            json ?: smbFail(ErrorCode.UNKNOWN, "location is required")
            return when (val kind = json.optString("kind")) {
                "smb" -> Smb(
                    sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() }
                        ?: smbFail(ErrorCode.UNKNOWN, "location.sessionId is required"),
                    share = json.optString("share").takeIf { it.isNotEmpty() }
                        ?: smbFail(ErrorCode.UNKNOWN, "location.share is required"),
                )
                "device" -> Device
                "volume" -> Volume(requireUuid(json.optString("uuid")))
                else -> smbFail(ErrorCode.UNKNOWN, "Unknown location kind: '$kind'")
            }
        }

        /**
         * Volume UUIDs are what Android puts in `/storage/<uuid>`: FAT/exFAT serials (`1A2B-3C4D`)
         * or filesystem UUIDs (hex and dashes). Anything else could escape the storage root.
         */
        fun requireUuid(uuid: String?): String {
            val value = uuid.orEmpty()
            if (!UUID_PATTERN.matches(value)) smbFail(ErrorCode.UNKNOWN, "Invalid volume uuid: '$value'")
            return value
        }

        private val UUID_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z-]{0,63}$")
    }
}
