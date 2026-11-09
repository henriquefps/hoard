package dev.hfps.hoard.image.source

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A file opened through a [ParcelFileDescriptor] (a Storage Access Framework document on a USB drive
 * or SD card without all files access). Positional reads on its [FileChannel]: no seeking state,
 * so header parsing and preview extraction read ranges exactly like a local file.
 */
class DescriptorSource(private val descriptor: ParcelFileDescriptor) : RandomAccessSource {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val channel: FileChannel = input.channel
    override val size: Long = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length == 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { descriptor.close() }
    }
}
