package dev.hfps.hoard.app.ui.common

import androidx.annotation.StringRes
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.FileFilter
import dev.hfps.hoard.app.files.FileKind
import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.route.AddressKind
import dev.hfps.hoard.smb.ErrorCode

/* Contract codes and enums → Hoard 1's words (strings_hoard1.xml, generated from the i18next files). */

@StringRes
fun errorTitle(code: ErrorCode): Int = when (code) {
    ErrorCode.UNREACHABLE -> R.string.t_errors_unreachable_title
    ErrorCode.TIMEOUT -> R.string.t_errors_timeout_title
    ErrorCode.AUTH_FAILED -> R.string.t_errors_auth_failed_title
    ErrorCode.GUID_MISMATCH -> R.string.t_errors_guid_mismatch_title
    ErrorCode.ACCESS_DENIED -> R.string.t_errors_access_denied_title
    ErrorCode.NOT_FOUND -> R.string.t_errors_not_found_title
    ErrorCode.ALREADY_EXISTS -> R.string.t_errors_already_exists_title
    ErrorCode.NO_SPACE -> R.string.t_errors_no_space_title
    ErrorCode.CANCELLED -> R.string.t_errors_cancelled_title
    ErrorCode.PERMISSION_REVOKED -> R.string.t_errors_permission_revoked_title
    ErrorCode.NO_APP -> R.string.t_errors_no_app_title
    ErrorCode.TOO_LARGE -> R.string.t_errors_too_large_title
    ErrorCode.NOT_TEXT -> R.string.t_errors_not_text_title
    ErrorCode.CONFLICT -> R.string.t_errors_conflict_title
    ErrorCode.STORAGE_PERMISSION -> R.string.t_errors_storage_permission_title
    ErrorCode.UNAVAILABLE -> R.string.t_errors_unavailable_title
    ErrorCode.UNKNOWN -> R.string.t_errors_unknown_title
}

@StringRes
fun errorBody(code: ErrorCode): Int = when (code) {
    ErrorCode.UNREACHABLE -> R.string.t_errors_unreachable_body
    ErrorCode.TIMEOUT -> R.string.t_errors_timeout_body
    ErrorCode.AUTH_FAILED -> R.string.t_errors_auth_failed_body
    ErrorCode.GUID_MISMATCH -> R.string.t_errors_guid_mismatch_body
    ErrorCode.ACCESS_DENIED -> R.string.t_errors_access_denied_body
    ErrorCode.NOT_FOUND -> R.string.t_errors_not_found_body
    ErrorCode.ALREADY_EXISTS -> R.string.t_errors_already_exists_body
    ErrorCode.NO_SPACE -> R.string.t_errors_no_space_body
    ErrorCode.CANCELLED -> R.string.t_errors_cancelled_body
    ErrorCode.PERMISSION_REVOKED -> R.string.t_errors_permission_revoked_body
    ErrorCode.NO_APP -> R.string.t_errors_no_app_body
    ErrorCode.TOO_LARGE -> R.string.t_errors_too_large_body
    ErrorCode.NOT_TEXT -> R.string.t_errors_not_text_body
    ErrorCode.CONFLICT -> R.string.t_errors_conflict_body
    ErrorCode.STORAGE_PERMISSION -> R.string.t_errors_storage_permission_body
    ErrorCode.UNAVAILABLE -> R.string.t_errors_unavailable_body
    ErrorCode.UNKNOWN -> R.string.t_errors_unknown_body
}

/** The short reason a route or a call failed ("Wrong user or password"). */
@StringRes
fun problemText(code: ErrorCode): Int = when (code) {
    ErrorCode.UNREACHABLE -> R.string.t_route_problem_unreachable
    ErrorCode.TIMEOUT -> R.string.t_route_problem_timeout
    ErrorCode.AUTH_FAILED -> R.string.t_route_problem_auth_failed
    ErrorCode.GUID_MISMATCH -> R.string.t_route_problem_guid_mismatch
    ErrorCode.ACCESS_DENIED -> R.string.t_route_problem_access_denied
    ErrorCode.NOT_FOUND -> R.string.t_route_problem_not_found
    ErrorCode.ALREADY_EXISTS -> R.string.t_route_problem_already_exists
    ErrorCode.NO_SPACE -> R.string.t_route_problem_no_space
    ErrorCode.CANCELLED -> R.string.t_route_problem_cancelled
    ErrorCode.PERMISSION_REVOKED -> R.string.t_route_problem_permission_revoked
    ErrorCode.NO_APP -> R.string.t_route_problem_no_app
    ErrorCode.TOO_LARGE -> R.string.t_route_problem_too_large
    ErrorCode.NOT_TEXT -> R.string.t_route_problem_not_text
    ErrorCode.CONFLICT -> R.string.t_route_problem_conflict
    ErrorCode.STORAGE_PERMISSION -> R.string.t_route_problem_storage_permission
    ErrorCode.UNAVAILABLE -> R.string.t_route_problem_unavailable
    ErrorCode.UNKNOWN -> R.string.t_route_problem_unknown
}

/** Why a file did not open ("The file is gone"). */
@StringRes
fun openErrorText(code: ErrorCode): Int = when (code) {
    ErrorCode.ACCESS_DENIED -> R.string.t_browse_open_error_access_denied
    ErrorCode.AUTH_FAILED -> R.string.t_browse_open_error_auth_failed
    ErrorCode.GUID_MISMATCH -> R.string.t_browse_open_error_guid_mismatch
    ErrorCode.NOT_FOUND -> R.string.t_browse_open_error_not_found
    ErrorCode.NO_APP -> R.string.t_browse_open_error_no_app
    ErrorCode.NO_SPACE -> R.string.t_browse_open_error_no_space
    ErrorCode.STORAGE_PERMISSION -> R.string.t_browse_open_error_storage_permission
    ErrorCode.TIMEOUT -> R.string.t_browse_open_error_timeout
    ErrorCode.UNAVAILABLE -> R.string.t_browse_open_error_unavailable
    ErrorCode.UNREACHABLE -> R.string.t_browse_open_error_unreachable
    else -> R.string.t_browse_open_error_unknown
}

@StringRes
fun addressKindText(kind: AddressKind): Int = when (kind) {
    AddressKind.Local -> R.string.t_address_kind_local
    AddressKind.Tailscale -> R.string.t_address_kind_tailscale
    AddressKind.Other -> R.string.t_address_kind_other
}

@StringRes
fun fileKindText(kind: FileKind): Int = when (kind) {
    FileKind.Folder -> R.string.t_file_kind_folder
    FileKind.Image -> R.string.t_file_kind_image
    FileKind.Video -> R.string.t_file_kind_video
    FileKind.Audio -> R.string.t_file_kind_audio
    FileKind.Document -> R.string.t_file_kind_document
    FileKind.Spreadsheet -> R.string.t_file_kind_spreadsheet
    FileKind.Pdf -> R.string.t_file_kind_pdf
    FileKind.Archive -> R.string.t_file_kind_archive
    FileKind.Code -> R.string.t_file_kind_code
    FileKind.Text -> R.string.t_file_kind_text
    FileKind.Other -> R.string.t_file_kind_other
}

@StringRes
fun filterText(filter: FileFilter): Int = when (filter) {
    FileFilter.All -> R.string.t_browse_filters_all
    FileFilter.Folders -> R.string.t_browse_filters_folders
    FileFilter.Images -> R.string.t_browse_filters_images
    FileFilter.Videos -> R.string.t_browse_filters_videos
    FileFilter.Audio -> R.string.t_browse_filters_audio
    FileFilter.Documents -> R.string.t_browse_filters_documents
    FileFilter.Archives -> R.string.t_browse_filters_archives
}

@StringRes
fun sortKeyText(key: SortKey): Int = when (key) {
    SortKey.Name -> R.string.t_browse_sort_keys_name
    SortKey.Size -> R.string.t_browse_sort_keys_size
    SortKey.Modified -> R.string.t_browse_sort_keys_modified
}

@StringRes
fun sortDirectionText(key: SortKey, ascending: Boolean): Int = when (key) {
    SortKey.Name -> if (ascending) R.string.t_browse_directions_name_asc else R.string.t_browse_directions_name_desc
    SortKey.Size -> if (ascending) R.string.t_browse_directions_size_asc else R.string.t_browse_directions_size_desc
    SortKey.Modified -> if (ascending) R.string.t_browse_directions_modified_asc else R.string.t_browse_directions_modified_desc
}

@StringRes
fun conflictText(policy: String): Int = when (policy) {
    "rename" -> R.string.t_conflict_rename
    "overwrite" -> R.string.t_conflict_overwrite
    "skip" -> R.string.t_conflict_skip
    else -> R.string.t_conflict_ask
}

@StringRes
fun conflictHint(policy: String): Int = when (policy) {
    "rename" -> R.string.t_conflict_rename_hint
    "overwrite" -> R.string.t_conflict_overwrite_hint
    "skip" -> R.string.t_conflict_skip_hint
    else -> R.string.t_conflict_ask_hint
}
