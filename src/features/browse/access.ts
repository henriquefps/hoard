import type { SmbAccess } from "@/plugins/smb/definitions"

/**
 * Read-only state of a share, folder or file, from the server's own answer (`SmbAccess`). Unknown
 * access (undefined) counts as writable: the server stays the final authority and a refused write
 * still shows its error.
 */
export const isReadOnly = (access: SmbAccess | undefined): boolean => access === "read"

/** The account name shown in "read-only for <user>" (a guest or empty username → null). */
export function accountName(username: string | null | undefined, domain?: string | null) {
  const user = username?.trim()
  if (!user) return null
  const realm = domain?.trim()
  return realm ? `${realm}\\${user}` : user
}

/**
 * The i18n key that explains why writing is off: the share root says "this share", any folder
 * below it "this folder" (the share may be writable while the folder is not).
 */
export function readOnlyReasonKey(path: string, hasUser: boolean) {
  const place = path === "" ? "share" : "folder"
  return `access.${place}ReadOnly${hasUser ? "" : "Anonymous"}` as const
}
