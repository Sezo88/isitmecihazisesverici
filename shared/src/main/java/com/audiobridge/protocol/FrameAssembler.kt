package com.audiobridge.protocol

/**
 * Reassembles 10 ms frames from the chunked datagrams produced by [FrameCodec].
 *
 * Chunks of a frame always arrive back to back, so only a handful of partially
 * filled frames can be pending at any time. The assembler is agnostic about
 * threading; call [add] and [clear] from a single thread.
 */
class FrameAssembler(private val maxPendingFrames: Int = 4) {

    class CompleteFrame(
        val sequence: Long,
        val timestampMs: Long,
        val flags: Int,
        val pcm: ByteArray,
    ) {
        val isMono: Boolean get() = (flags and Protocol.FLAG_MONO) != 0
        val isClickTrack: Boolean get() = (flags and Protocol.FLAG_CLICK_TRACK) != 0
    }

    private class Partial(val frameBytes: Int, val chunkCount: Int) {
        val payload = ByteArray(frameBytes)
        val received = BooleanArray(chunkCount)
        var receivedCount = 0
    }

    private val pending = LinkedHashMap<Long, Partial>()

    @Synchronized
    fun add(header: FrameHeader, data: ByteArray, offset: Int, length: Int): CompleteFrame? {
        if (header.frameBytes <= 0 || header.frameBytes > Protocol.MAX_FRAME_BYTES) return null
        if (header.chunkIndex >= header.chunkCount) return null
        if (length <= 0) return null

        val frameOffset = header.chunkIndex * Protocol.MAX_PAYLOAD
        if (frameOffset >= header.frameBytes) return null
        val copyLength = minOf(length, header.frameBytes - frameOffset)
        if (copyLength <= 0) return null

        var partial = pending[header.sequence]
        if (partial != null &&
            (partial.frameBytes != header.frameBytes || partial.chunkCount != header.chunkCount)
        ) {
            // Conflicting metadata for the same sequence number: start over.
            pending.remove(header.sequence)
            partial = null
        }
        if (partial == null) {
            partial = Partial(header.frameBytes, header.chunkCount)
            pending[header.sequence] = partial
            trim()
        }

        System.arraycopy(data, offset, partial.payload, frameOffset, copyLength)

        if (!partial.received[header.chunkIndex]) {
            partial.received[header.chunkIndex] = true
            partial.receivedCount++
        }

        if (partial.receivedCount < partial.chunkCount) return null

        pending.remove(header.sequence)
        return CompleteFrame(
            sequence = header.sequence,
            timestampMs = header.timestampMs,
            flags = header.flags,
            pcm = partial.payload,
        )
    }

    @Synchronized
    fun clear() = pending.clear()

    private fun trim() {
        while (pending.size > maxPendingFrames) {
            val oldest = pending.keys.firstOrNull() ?: return
            pending.remove(oldest)
        }
    }
}
