package dev.hfps.hoard.app.trash

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.location.PathFs
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** `HoardFs.move` on path storage (ADR 0023): one rename, never a replace, never a copy; the target's parent must exist. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PathFsMoveTest {
    private val root: File = Files.createTempDirectory("pathfs-move").toFile()
    private val fs = PathFs(ApplicationProvider.getApplicationContext(), root, "volume:test", "Test", readOnly = false, sdkInt = 35)

    @After fun clean() {
        root.deleteRecursively()
    }

    private fun file(path: String, text: String = "x", modified: Long = 1_600_000_000_000L) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
        setLastModified(modified)
    }

    private fun code(block: () -> Unit) = assertThrows(HoardSmbException::class.java) { block() }.code

    @Test
    fun movesAcrossFoldersKeepingTheModifiedTime() {
        file("DCIM/a.jpg", "abc")
        File(root, ".hoard-trash/2026-11-15/DCIM").mkdirs()
        val entry = fs.move("DCIM/a.jpg", ".hoard-trash/2026-11-15/DCIM/a.jpg")
        assertThat(entry.path).isEqualTo(".hoard-trash/2026-11-15/DCIM/a.jpg")
        assertThat(File(root, "DCIM/a.jpg").exists()).isFalse()
        val moved = File(root, ".hoard-trash/2026-11-15/DCIM/a.jpg")
        assertThat(moved.readText()).isEqualTo("abc")
        assertThat(moved.lastModified()).isEqualTo(1_600_000_000_000L)
    }

    @Test
    fun neverReplacesAndNeedsTheParent() {
        file("a.jpg", "new")
        file("b/a.jpg", "old")
        assertThat(code { fs.move("a.jpg", "b/a.jpg") }).isEqualTo(ErrorCode.ALREADY_EXISTS)
        assertThat(File(root, "b/a.jpg").readText()).isEqualTo("old")
        assertThat(code { fs.move("a.jpg", "missing/a.jpg") }).isEqualTo(ErrorCode.NOT_FOUND)
        assertThat(code { fs.move("gone.jpg", "b/gone.jpg") }).isEqualTo(ErrorCode.NOT_FOUND)
        File(root, "dir/sub").mkdirs()
        assertThat(code { fs.move("dir", "dir/sub/dir") }).isEqualTo(ErrorCode.ACCESS_DENIED)
        assertThat(code { fs.move("a.jpg", "") }).isEqualTo(ErrorCode.ACCESS_DENIED)
    }

    @Test
    fun aReadOnlyVolumeRefuses() {
        file("a.jpg")
        File(root, "t").mkdirs()
        val ro = PathFs(ApplicationProvider.getApplicationContext(), root, "volume:test", "Test", readOnly = true, sdkInt = 35)
        assertThat(code { ro.move("a.jpg", "t/a.jpg") }).isEqualTo(ErrorCode.ACCESS_DENIED)
        assertThat(fs.moveRootOf("a.jpg")).isEqualTo("")
    }
}
