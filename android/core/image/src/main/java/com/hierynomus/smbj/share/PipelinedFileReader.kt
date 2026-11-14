// Lives in smbj's package to reach Share.readAsync / Share.getReadBufferSize, which are
// package-private. smbj's own FileInputStream keeps a single READ in flight, so every chunk costs a
// full round trip; over Tailscale/Wi-Fi that caps throughput at chunk / RTT. Keeping several READs in
// flight hides the latency (the same thing Windows Explorer does).
package com.hierynomus.smbj.share

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Sequential reader of [file] from [startOffset] to [endOffset] with up to [depth] READ requests in
 * flight. [next] returns chunks strictly in file order; null at the end of the file.
 *
 * [depth] can change between calls: the stream reader starts a fresh pipeline at depth 1 (a seek
 * may be followed by a single small read) and deepens it once reads turn out to be sequential.
 */
class PipelinedFileReader(
    private val file: File,
    startOffset: Long,
    private val endOffset: Long,
    var depth: Int = 4,
    private val timeoutMs: Long = 30_000,
) {
    private class Pending(val offset: Long, val length: Int, val future: Future<SMB2ReadResponse>)

    private val share: Share = file.diskShare
    private val chunkSize: Int = share.readBufferSize.coerceAtLeast(1)
    private val inFlight = ArrayDeque<Pending>()

    /** Offset of the next byte [next] will return. */
    var position: Long = startOffset
        private set

    /** Offset the next READ request will ask for. */
    private var requestOffset: Long = startOffset

    private fun fill() {
        while (inFlight.size < depth && requestOffset < endOffset) {
            val length = minOf(chunkSize.toLong(), endOffset - requestOffset).toInt()
            inFlight.addLast(Pending(requestOffset, length, share.readAsync(file.fileId, requestOffset, length)))
            requestOffset += length
        }
    }

    fun next(): ByteArray? {
        if (position >= endOffset) return null
        fill()
        val pending = inFlight.pollFirst() ?: return null
        val response = pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        val status = response.header.statusCode
        if (status == NtStatus.STATUS_END_OF_FILE.value || (status == NtStatus.STATUS_SUCCESS.value && response.dataLength == 0)) {
            // The file shrank under us; the caller compares position with the expected size.
            inFlight.clear()
            requestOffset = position
            return null
        }
        if (status != NtStatus.STATUS_SUCCESS.value) {
            throw SMBApiException(response.header, "Read failed at offset ${pending.offset}")
        }
        val data = response.data
        val received = minOf(response.dataLength, data.size)
        position = pending.offset + received
        if (received < pending.length) {
            // Short read: the requests already in flight assumed full chunks. Drop them (their answers
            // are ignored) and continue right after what we actually got.
            inFlight.clear()
            requestOffset = position
        }
        return if (received == data.size) data else data.copyOf(received)
    }

    /** Responses still in flight are simply ignored; closing the file handle is the caller's job. */
    fun abandon() = inFlight.clear()
}
