import { describe, expect, it } from "vitest"
import { accountName, isReadOnly, readOnlyReasonKey } from "./access"

describe("read-only state", () => {
  it("is read-only only when the server said so", () => {
    expect(isReadOnly("read")).toBe(true)
    expect(isReadOnly("readWrite")).toBe(false)
    // Unknown access leaves the actions on: the server's error is the final authority.
    expect(isReadOnly(undefined)).toBe(false)
  })

  it("names the account", () => {
    expect(accountName("henrique")).toBe("henrique")
    expect(accountName(" henrique ", "")).toBe("henrique")
    expect(accountName("ana", "OFFICE")).toBe("OFFICE\\ana")
    expect(accountName("")).toBeNull()
    expect(accountName(null)).toBeNull()
  })

  it("explains the share root and folders differently", () => {
    expect(readOnlyReasonKey("", true)).toBe("access.shareReadOnly")
    expect(readOnlyReasonKey("a/b", true)).toBe("access.folderReadOnly")
    expect(readOnlyReasonKey("", false)).toBe("access.shareReadOnlyAnonymous")
    expect(readOnlyReasonKey("a", false)).toBe("access.folderReadOnlyAnonymous")
  })
})
