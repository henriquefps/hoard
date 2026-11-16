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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw trashError(e)
    }

    companion object {
        /**
         * NT statuses that say the session or the share went away (`USER_SESSION_DELETED`, `NETWORK_SESSION_EXPIRED`,
         * `NETWORK_NAME_DELETED`, `BAD_NETWORK_NAME`). `SmbErrors` maps them to `NOT_FOUND` for Files (the share list
         * is read again); in the trash a "not found" listing would forget a whole root's rows, so they are
         * `UNREACHABLE` here: the root shows as not reachable and nothing is forgotten.
         */
        private val GONE = setOf(0xC0000203L, 0xC000035CL, 0xC00000C9L, 0xC00000CCL)

        fun trashError(e: Throwable): HoardSmbException {
            val gone = generateSequence(e) { it.cause }.take(12).any { c ->
                c is com.hierynomus.mssmb2.SMBApiException && (c.statusCode and 0xFFFFFFFFL) in GONE
            }
            val mapped = e as? HoardSmbException ?: SmbErrors.map(e)
            return if (gone) HoardSmbException(dev.hfps.hoard.smb.ErrorCode.UNREACHABLE, mapped.message ?: "share gone", e) else mapped
        }
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
