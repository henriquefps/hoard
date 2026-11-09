package dev.hfps.hoard.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the "every share shows the same folder" bug: smbj 0.15.0's `DiskShare.list(path, …)`
 * and `DiskShare.openDirectory(…)` go through a connection-wide directory-lease cache keyed by the
 * path inside the share only, so on macOS/Windows servers one share's listing was served for every
 * other share. Directory listings must go through [SmbBrowser.listDirectory] (plain CREATE without
 * a lease context). The live check is the opt-in SmbMultiShareTest.
 */
class DirectoryListingGuardTest {

    private val forbidden = listOf(
        Regex("""\.list\([^,()]+,\s*FileIdBothDirectoryInformation"""), // DiskShare.list(path, class)
        Regex("""\.list\(\s*"[^"]*"\s*[,)]"""), // DiskShare.list("path")
        Regex("""\bopenDirectory\("""),
    )

    @Test
    fun `native code never lists through smbj's leased directory cache`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> forbidden.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("use SmbBrowser.listDirectory instead", emptyList<String>(), offenders)
    }
}
