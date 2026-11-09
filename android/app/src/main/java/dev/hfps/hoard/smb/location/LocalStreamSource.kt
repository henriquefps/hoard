package dev.hfps.hoard.smb.location

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.stream.StreamReader
import dev.hfps.hoard.smb.stream.StreamSource
import dev.hfps.hoard.smb.viewer.RemoteFileInfo
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * A local location as a [StreamSource] for the local web server (`openWebPage` on a phone or USB
 * folder): the same tokens, paths rules and viewer as SMB pages, reading the files directly.
 */
class LocalStreamSource(private val context: Context, private val fs: LocalFs) : StreamSource {

    override val label: String get() = fs.label

    override fun stat(path: String): RemoteFileInfo? = try {
        val file = fs.file(path)
        RemoteFileInfo(file.size, file.modifiedMillis, file.isDirectory)
    } catch (e: Exception) {
        null
    }

    override fun list(path: String): List<Pair<String, Boolean>> = fs.names(path)

    override fun reader(path: String, size: Long, fetched: AtomicLong): StreamReader {
        val file = fs.file(SmbPaths.normalize(path))
        val channel: FileChannel = when {
            file.file != null -> RandomAccessFile(file.file, "r").channel
            else -> {
                val pfd = context.contentResolver.openFileDescriptor(file.documentUri!!, "r")
                    ?: throw IOException("${file.name} could not be opened")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
            }
        }
        return ChannelReader(channel, size, fetched)
    }

    override fun close() = Unit

    private class ChannelReader(private val channel: FileChannel, private val size: Long, private val fetched: AtomicLong) : StreamReader {
        override fun read(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
            if (offset >= size || length <= 0) return 0
            val wanted = minOf(length.toLong(), size - offset).toInt()
            val buffer = ByteBuffer.wrap(out, outOffset, wanted)
            var position = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n <= 0) break
                position += n
            }
            val done = (position - offset).toInt()
            fetched.addAndGet(done.toLong())
            return done
        }

        override fun close() = channel.close()
    }
}
