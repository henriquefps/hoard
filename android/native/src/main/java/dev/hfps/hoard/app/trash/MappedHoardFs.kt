package dev.hfps.hoard.app.trash

import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.location.HoardFs
import kotlin.coroutines.cancellation.CancellationException

/**
 * A [HoardFs] whose every failure is a [HoardSmbException] with its contract code. `SmbFs` throws smbj's own
 * exceptions (`FileService` maps them only around a whole block), and the trash decides per call: a `stat` that says
 * "not found" means a free path, not a refusal.
 */
class MappedHoardFs(private val fs: HoardFs) : HoardFs {
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: HoardSmbException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SmbErrors.map(e)
    }

    override fun list(path: String, cancellation: Cancellation) = mapped { fs.list(path, cancellation) }
    override fun stat(path: String) = mapped { fs.stat(path) }
    override fun createFolder(path: String, name: String) = mapped { fs.createFolder(path, name) }
    override fun rename(path: String, newName: String) = mapped { fs.rename(path, newName) }
    override fun move(path: String, targetPath: String) = mapped { fs.move(path, targetPath) }
    override fun moveRootOf(path: String) = mapped { fs.moveRootOf(path) }
    override fun delete(path: String, recursive: Boolean, cancellation: Cancellation) = mapped { fs.delete(path, recursive, cancellation) }
    override fun readText(path: String, maxBytes: Int, truncate: Boolean) = mapped { fs.readText(path, maxBytes, truncate) }
    override fun writeText(path: String, text: String, encoding: String, mode: String, expectedModifiedAt: String?) =
        mapped { fs.writeText(path, text, encoding, mode, expectedModifiedAt) }
}
