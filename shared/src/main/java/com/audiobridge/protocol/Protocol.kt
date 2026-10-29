package com.audiobridge.protocol

/**
 * Wire format constants shared by the sender and the receiver.
 *
 * Audio is raw little-endian PCM, 48 kHz, 16-bit.
 * A "frame" is 10 ms of audio (480 samples per channel, 1920 bytes stereo / 960 bytes mono).
 *
 * Each frame is cut into datagram-sized chunks so that no IP fragmentation is ever needed
 * (a 10 ms stereo frame would otherwise be ~1940 bytes and would fragment on every packet).
 *
 * Datagram layout (20 byte header, little-endian):
 *
 *   0  uint16  magic        0xAB01
 *   2  uint8   version      1
 *   3  uint8   flags        bit0 = mono, bit1 = click-track
 *   4  uint32  streamId     random per capture session, lets the receiver drop stale packets
 *   8  uint32  sequence     frame counter, increases by 1 per 10 ms frame
 * 12  uint32  timestampMs  sender monotonic clock (ms) at frame capture
 * 16  uint8   chunkIndex   0-based index of this chunk inside the frame
 * 17  uint8   chunkCount   number of chunks of this frame
 * 18  uint16  frameBytes   total PCM bytes of the whole frame
 * 20  ...     payload      PCM chunk
 */
object Protocol {

    const val MAGIC: Int = 0xAB01
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 20

    const val SAMPLE_RATE: Int = 48_000
    const val CHANNELS: Int = 2
    const val BYTES_PER_SAMPLE: Int = 2

    /** Frame duration in milliseconds. */
    const val FRAME_MS: Int = 10
    const val FRAME_SAMPLES: Int = SAMPLE_RATE * FRAME_MS / 1000          // 480 per channel
    const val FRAME_BYTES_STEREO: Int = FRAME_SAMPLES * CHANNELS * BYTES_PER_SAMPLE   // 1920
    const val FRAME_BYTES_MONO: Int = FRAME_SAMPLES * BYTES_PER_SAMPLE                 // 960

    /** Default UDP port. The receiver advertises the real port over mDNS. */
    const val DEFAULT_PORT: Int = 47811

    /** mDNS / DNS-SD service type. */
    const val SERVICE_TYPE: String = "_audiobridge._udp."

    /** Max PCM bytes per datagram: header + 1200 = 1220 bytes, safely under a 1500 byte MTU. */
    const val MAX_PAYLOAD: Int = 1200

    const val MAX_FRAME_BYTES: Int = 4096
    const val MAX_CHUNKS: Int = 8

    const val FLAG_MONO: Int = 1
    const val FLAG_CLICK_TRACK: Int = 1 shl 1

    /** Nominal bitrate for stereo PCM: usable for sanity checks and UI hints. */
    const val STEREO_BITRATE_KBPS: Int = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * 8 / 1000

    fun chunksFor(frameBytes: Int): Int =
        if (frameBytes <= 0) 0 else (frameBytes + MAX_PAYLOAD - 1) / MAX_PAYLOAD
}

/** Parsed header of a single datagram. */
data class FrameHeader(
    val flags: Int,
    val streamId: Int,
    val sequence: Long,
    val timestampMs: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val frameBytes: Int,
) {
    val isMono: Boolean get() = (flags and Protocol.FLAG_MONO) != 0
    val isClickTrack: Boolean get() = (flags and Protocol.FLAG_CLICK_TRACK) != 0
}

/** Result of decoding one datagram: header plus the slice of the buffer holding PCM. */
class DecodedPacket(
    val header: FrameHeader,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/**
 * Binary codec for the datagram format described in [Protocol].
 * Allocation free on the hot path: the sender encodes into a reusable buffer.
 */
object FrameCodec {

    /**
     * Writes header + payload into [out] and returns the number of bytes to send.
     * [out] must be at least [Protocol.HEADER_SIZE] + [payloadLength] bytes long.
     */
    fun encodeInto(
        header: FrameHeader,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
        out: ByteArray,
    ): Int {
        require(payloadLength in 1..Protocol.MAX_PAYLOAD) { "bad payload length $payloadLength" }
        require(payloadOffset >= 0 && payloadOffset + payloadLength <= payload.size) { "payload slice out of bounds" }
        require(out.size >= Protocol.HEADER_SIZE + payloadLength) { "output buffer too small" }

        out[0] = (Protocol.MAGIC and 0xFF).toByte()
        out[1] = ((Protocol.MAGIC ushr 8) and 0xFF).toByte()
        out[2] = Protocol.VERSION.toByte()
        out[3] = header.flags.toByte()
        putIntLe(out, 4, header.streamId)
        putIntLe(out, 8, header.sequence.toInt())
        putIntLe(out, 12, header.timestampMs.toInt())
        out[16] = header.chunkIndex.toByte()
        out[17] = header.chunkCount.toByte()
        out[18] = (header.frameBytes and 0xFF).toByte()
        out[19] = ((header.frameBytes ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, payloadOffset, out, Protocol.HEADER_SIZE, payloadLength)
        return Protocol.HEADER_SIZE + payloadLength
    }

    /** Convenience wrapper used by tests. */
    fun encode(header: FrameHeader, payload: ByteArray, payloadOffset: Int, payloadLength: Int): ByteArray {
        val out = ByteArray(Protocol.HEADER_SIZE + payloadLength)
        encodeInto(header, payload, payloadOffset, payloadLength, out)
        return out
    }

    /** Returns null for anything that is not a well formed audio datagram. */
    fun decode(data: ByteArray, length: Int): DecodedPacket? {
        if (length < Protocol.HEADER_SIZE + 1) return null

        val magic = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (magic != Protocol.MAGIC) return null
        if ((data[2].toInt() and 0xFF) != Protocol.VERSION) return null

        val flags = data[3].toInt() and 0xFF
        val streamId = readIntLe(data, 4)
        val sequence = readIntLe(data, 8).toLong() and 0xFFFF_FFFFL
        val timestampMs = readIntLe(data, 12).toLong() and 0xFFFF_FFFFL
        val chunkIndex = data[16].toInt() and 0xFF
        val chunkCount = data[17].toInt() and 0xFF
        val frameBytes = (data[18].toInt() and 0xFF) or ((data[19].toInt() and 0xFF) shl 8)

        if (chunkCount !in 1..Protocol.MAX_CHUNKS) return null
        if (chunkIndex >= chunkCount) return null
        if (frameBytes <= 0 || frameBytes > Protocol.MAX_FRAME_BYTES) return null

        val payloadLength = length - Protocol.HEADER_SIZE
        if (payloadLength <= 0) return null

        val frameOffset = chunkIndex * Protocol.MAX_PAYLOAD
        if (frameOffset >= frameBytes) return null
        if (payloadLength > frameBytes - frameOffset) return null

        return DecodedPacket(
            FrameHeader(flags, streamId, sequence, timestampMs, chunkIndex, chunkCount, frameBytes),
            Protocol.HEADER_SIZE,
            payloadLength,
        )
    }

    private fun putIntLe(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
