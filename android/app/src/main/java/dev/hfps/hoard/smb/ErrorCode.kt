package dev.hfps.hoard.smb

/** Mirrors `HOARD_SMB_ERROR_CODES` in `src/plugins/smb/definitions.ts`; pass `.name` to `call.reject`. */
enum class ErrorCode {
    UNREACHABLE,
    TIMEOUT,
    AUTH_FAILED,
    GUID_MISMATCH,
    ACCESS_DENIED,
    NOT_FOUND,
    ALREADY_EXISTS,
    NO_SPACE,
    CANCELLED,
    PERMISSION_REVOKED,
    NO_APP,
    UNKNOWN;

    companion object {
        fun fromName(name: String?): ErrorCode = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * A failure that already carries its contract code. Everything native throws is converted to this
 * (see [SmbErrors.map]) before it reaches `call.reject`, so every rejection has a `HoardSmbErrorCode`.
 *
 * @param data extra fields for `error.data` (e.g. `serverGuid` on [ErrorCode.GUID_MISMATCH]).
 */
class HoardSmbException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
    val data: Map<String, Any?> = emptyMap(),
) : Exception(message, cause)

/** Shorthand for throwing a contract error. */
fun smbFail(code: ErrorCode, message: String, data: Map<String, Any?> = emptyMap()): Nothing =
    throw HoardSmbException(code, message, data = data)
