package com.audiobridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    private fun header(sequence: Long, chunkIndex: Int = 0, chunkCount: Int = 1) = FrameHeader(
        flags = Protocol.FLAG_MONO or Protocol.FLAG_CLICK_TRACK,
        streamId = -1234567,
        sequence = sequence,
        timestampMs = 987_654_321L,
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        frameBytes = Protocol.FRAME_BYTES_STEREO,
    )

    @Test
    fun `roundtrip single chunk frame`() {
        // a mono 10 ms frame (960 bytes) fits into a single datagram
        val pcm = ByteArray(Protocol.FRAME_BYTES_MONO) { (it * 7).toByte() }
        val single = FrameHeader(
            flags = Protocol.FLAG_MONO or Protocol.FLAG_CLICK_TRACK,
            streamId = -1234567,
            sequence = 42,
            timestampMs = 987_654_321L,
            chunkIndex = 0,
            chunkCount = 1,
            frameBytes = pcm.size,
        )
        val datagram = FrameCodec.encode(single, pcm, 0, pcm.size)

        val decoded = FrameCodec.decode(datagram, datagram.size)
        assertNotNull(decoded)
        val h = decoded!!.header
        assertEquals(42L, h.sequence)
        assertEquals(-1234567, h.streamId)
        assertEquals(987_654_321L, h.timestampMs)
        assertEquals(0, h.chunkIndex)
        assertEquals(1, h.chunkCount)
        assertEquals(Protocol.FRAME_BYTES_MONO, h.frameBytes)
        assertTrue(h.isMono)
        assertTrue(h.isClickTrack)
        assertEquals(pcm.size, decoded.payloadLength)
        assertArrayEquals(pcm, datagram.copyOfRange(decoded.payloadOffset, decoded.payloadOffset + decoded.payloadLength))
    }

    @Test
    fun `roundtrip chunked stereo frame`() {
        // a stereo 10 ms frame (1920 bytes) is sent as two datagrams
        val pcm = ByteArray(Protocol.FRAME_BYTES_STEREO) { (it * 7).toByte() }
        val chunkCount = Protocol.chunksFor(pcm.size)
        assertEquals(2, chunkCount)

        var reassembled = ByteArray(pcm.size)
        for (chunk in 0 until chunkCount) {
            val offset = chunk * Protocol.MAX_PAYLOAD
            val length = minOf(Protocol.MAX_PAYLOAD, pcm.size - offset)
            val h = FrameHeader(0, 99, 7, 70, chunk, chunkCount, pcm.size)
            val datagram = FrameCodec.encode(h, pcm, offset, length)
            val decoded = FrameCodec.decode(datagram, datagram.size)!!
            assertEquals(chunk, decoded.header.chunkIndex)
            assertEquals(chunkCount, decoded.header.chunkCount)
            System.arraycopy(datagram, decoded.payloadOffset, reassembled, offset, decoded.payloadLength)
        }
        assertArrayEquals(pcm, reassembled)
    }

    @Test
    fun `roundtrip across the full uint32 range`() {
        val pcm = ByteArray(64) { 3 }
        val big = header(0xFFFF_FFFDL)
        val datagram = FrameCodec.encode(big, pcm, 0, pcm.size)
        assertEquals(0xFFFF_FFFDL, FrameCodec.decode(datagram, datagram.size)!!.header.sequence)
    }

    @Test
    fun `wrong magic is rejected`() {
        val pcm = ByteArray(64)
        val datagram = FrameCodec.encode(header(1), pcm, 0, pcm.size)
        datagram[0] = 0
        assertNull(FrameCodec.decode(datagram, datagram.size))
    }

    @Test
    fun `wrong version is rejected`() {
        val pcm = ByteArray(64)
        val datagram = FrameCodec.encode(header(1), pcm, 0, pcm.size)
        datagram[2] = 99
        assertNull(FrameCodec.decode(datagram, datagram.size))
    }

    @Test
    fun `truncated and empty datagrams are rejected`() {
        val pcm = ByteArray(64)
        val datagram = FrameCodec.encode(header(1), pcm, 0, pcm.size)
        assertNull(FrameCodec.decode(datagram, Protocol.HEADER_SIZE))
        assertNull(FrameCodec.decode(datagram, 0))
        assertNull(FrameCodec.decode(ByteArray(4), 4))
    }

    @Test
    fun `chunk index beyond count is rejected`() {
        val pcm = ByteArray(64)
        val datagram = FrameCodec.encode(header(1, chunkIndex = 3, chunkCount = 2), pcm, 0, pcm.size)
        assertNull(FrameCodec.decode(datagram, datagram.size))
    }

    @Test
    fun `payload longer than the frame remainder is rejected`() {
        // frameBytes says 32 bytes, but the chunk payload is 64
        val pcm = ByteArray(64) { 5 }
        val h = FrameHeader(0, 1, 1, 1, 1, 2, 32)
        val datagram = FrameCodec.encode(h, pcm, 0, pcm.size)
        assertNull(FrameCodec.decode(datagram, datagram.size))
    }

    @Test
    fun `ten millisecond stereo frame needs two chunks`() {
        assertEquals(2, Protocol.chunksFor(Protocol.FRAME_BYTES_STEREO))
        assertEquals(1, Protocol.chunksFor(Protocol.FRAME_BYTES_MONO))
        assertTrue(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD < 1472)
    }

    @Test
    fun `encodeInto writes header before payload`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val out = ByteArray(Protocol.HEADER_SIZE + 4)
        val length = FrameCodec.encodeInto(header(7), pcm, 0, 4, out)
        assertEquals(Protocol.HEADER_SIZE + 4, length)
        assertEquals(1.toByte(), out[Protocol.HEADER_SIZE])
        assertEquals(4.toByte(), out[Protocol.HEADER_SIZE + 3])
        assertEquals(0xAB01 and 0xFF, out[0].toInt() and 0xFF)
        assertEquals(Protocol.VERSION, out[2].toInt())
    }
}
