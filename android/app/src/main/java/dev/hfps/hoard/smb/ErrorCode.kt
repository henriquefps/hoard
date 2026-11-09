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
    UNKNOWN,
}
