package dev.hfps.hoard.bridge

import dev.hfps.hoard.smb.bridge.ImportBridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who the import bridge answers (ADR 0019): Hoard Next, signed with Hoard's key; nobody else. */
class ImportBridgeContractTest {
    @Test
    fun onlyHoardNextWithTheSameSignature() {
        assertTrue(ImportBridgeContract.allowed("dev.hfps.hoard.next", sameSignature = true))
        assertFalse(ImportBridgeContract.allowed("dev.hfps.hoard.next", sameSignature = false))
        assertFalse(ImportBridgeContract.allowed("dev.hfps.reveal", sameSignature = true))
        assertFalse(ImportBridgeContract.allowed("dev.hfps.hoard", sameSignature = true))
        assertFalse(ImportBridgeContract.allowed(null, sameSignature = true))
    }

    @Test
    fun urisAreStable() {
        assertEquals("content://dev.hfps.hoard.importbridge/database", ImportBridgeContract.uri(ImportBridgeContract.PATH_DATABASE))
        assertEquals("content://dev.hfps.hoard.importbridge/settings.json", ImportBridgeContract.uri(ImportBridgeContract.PATH_SETTINGS))
    }
}
