package com.audiobridge.receiver

import com.audiobridge.protocol.FrameAssembler
import com.audiobridge.protocol.FrameCodec
import com.audiobridge.protocol.FrameHeader
import com.audiobridge.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private fun stereoFrame(seed: Int = 0): ByteArray =
        ByteArray(Protocol.FRAME_BYTES_STEREO) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun send(
        assembler: FrameAssembler,
        pcm: ByteArray,
        sequence: Long,
        timestamp: Long = sequence * Protocol.FRAME_MS,
        flags: Int = 0,
    ): FrameAssembler.CompleteFrame? {
        val chunkCount = Protocol.chunksFor(pcm.size)
        var complete: FrameAssembler.CompleteFrame? = null
        for (chunk in 0 until chunkCount) {
            val offset = chunk * Protocol.MAX_PAYLOAD
            val length = minOf(Protocol.MAX_PAYLOAD, pcm.size - offset)
            val header = FrameHeader(flags, 7, sequence, timestamp, chunk, chunkCount, pcm.size)
            val datagram = FrameCodec.encode(header, pcm, offset, length)
            val decoded = FrameCodec.decode(datagram, datagram.size)!!
            val result = assembler.add(decoded.header, datagram, decoded.payloadOffset, decoded.payloadLength)
            if (result != null) complete = result
        }
        return complete
    }

    @Test
    fun `a stereo frame split into two chunks reassembles exactly`() {
        val assembler = FrameAssembler()
        val pcm = stereoFrame(3)
        assertEquals(2, Protocol.chunksFor(pcm.size))

        val frame = send(assembler, pcm, 12)

        assertNotNull(frame)
        assertEquals(12L, frame!!.sequence)
        assertEquals(12L * Protocol.FRAME_MS, frame.timestampMs)
        assertEquals(pcm.size, frame.pcm.size)
        assertArrayEquals(pcm, frame.pcm)
    }

    @Test
    fun `a mono frame reassembles and reports its flags`() {
        val assembler = FrameAssembler()
        val pcm = ByteArray(Protocol.FRAME_BYTES_MONO) { 9 }

        val frame = send(assembler, pcm, 4, flags = Protocol.FLAG_MONO)

        assertNotNull(frame)
        assertTrue(frame!!.isMono)
        assertEquals(Protocol.FRAME_BYTES_MONO, frame.pcm.size)
    }

    @Test
    fun `missing chunk means the frame is never delivered`() {
        val assembler = FrameAssembler()
        val pcm = stereoFrame()
        val header0 = FrameHeader(0, 7, 0, 0, 0, 2, pcm.size)
        val chunk0 = FrameCodec.encode(header0, pcm, 0, Protocol.MAX_PAYLOAD)
        val decoded0 = FrameCodec.decode(chunk0, chunk0.size)!!
        assertNull(assembler.add(decoded0.header, chunk0, decoded0.payloadOffset, decoded0.payloadLength))

        // the next frame completes fine even though frame 0 is incomplete
        val next = send(assembler, stereoFrame(1), 1)
        assertNotNull(next)
        assertEquals(1L, next!!.sequence)
    }

    @Test
    fun `duplicate chunk does not corrupt the frame`() {
        val assembler = FrameAssembler()
        val pcm = stereoFrame(5)

        val header0 = FrameHeader(0, 7, 9, 90, 0, 2, pcm.size)
        val chunk0 = FrameCodec.encode(header0, pcm, 0, Protocol.MAX_PAYLOAD)
        val decoded0 = FrameCodec.decode(chunk0, chunk0.size)!!
        assembler.add(decoded0.header, chunk0, decoded0.payloadOffset, decoded0.payloadLength)
        // duplicate delivery of chunk 0
        assembler.add(decoded0.header, chunk0, decoded0.payloadOffset, decoded0.payloadLength)

        val header1 = FrameHeader(0, 7, 9, 90, 1, 2, pcm.size)
        val chunk1 = FrameCodec.encode(header1, pcm, Protocol.MAX_PAYLOAD, pcm.size - Protocol.MAX_PAYLOAD)
        val decoded1 = FrameCodec.decode(chunk1, chunk1.size)!!
        val frame = assembler.add(decoded1.header, chunk1, decoded1.payloadOffset, decoded1.payloadLength)

        assertNotNull(frame)
        assertArrayEquals(pcm, frame!!.pcm)
    }

    @Test
    fun `conflicting metadata restarts the frame`() {
        val assembler = FrameAssembler()
        val pcm = stereoFrame()

        // first a chunk claiming 2 chunks total
        val a = FrameHeader(0, 7, 3, 30, 0, 2, pcm.size)
        val da = FrameCodec.encode(a, pcm, 0, Protocol.MAX_PAYLOAD)
        val decodedA = FrameCodec.decode(da, da.size)!!
        assembler.add(decodedA.header, da, decodedA.payloadOffset, decodedA.payloadLength)

        // then the same sequence arrives claiming 1 chunk (different sender attempt)
        val b = FrameHeader(0, 7, 3, 30, 0, 1, Protocol.MAX_PAYLOAD)
        val db = FrameCodec.encode(b, pcm, 0, Protocol.MAX_PAYLOAD)
        val decodedB = FrameCodec.decode(db, db.size)!!
        val frame = assembler.add(decodedB.header, db, decodedB.payloadOffset, decodedB.payloadLength)

        assertNotNull(frame)
        assertEquals(Protocol.MAX_PAYLOAD, frame!!.pcm.size)
    }

    @Test
    fun `pending frames are bounded`() {
        val assembler = FrameAssembler(maxPendingFrames = 2)
        val pcm = stereoFrame()
        val chunkCount = Protocol.chunksFor(pcm.size)
        assertEquals(2, chunkCount)

        // start four partial frames, none complete
        for (seq in 0..3L) {
            val header = FrameHeader(0, 7, seq, 0, 0, chunkCount, pcm.size)
            val datagram = FrameCodec.encode(header, pcm, 0, Protocol.MAX_PAYLOAD)
            val decoded = FrameCodec.decode(datagram, datagram.size)!!
            assertNull(assembler.add(decoded.header, datagram, decoded.payloadOffset, decoded.payloadLength))
        }
        // the oldest partial frames were evicted; completing seq 3 still works
        val tailLength = pcm.size - Protocol.MAX_PAYLOAD
        val last = FrameHeader(0, 7, 3, 0, 1, chunkCount, pcm.size)
        val datagram = FrameCodec.encode(last, pcm, Protocol.MAX_PAYLOAD, tailLength)
        val decoded = FrameCodec.decode(datagram, datagram.size)!!
        val frame = assembler.add(decoded.header, datagram, decoded.payloadOffset, decoded.payloadLength)
        assertNotNull(frame)
        assertArrayEquals(pcm, frame!!.pcm)
    }

    @Test
    fun `clear drops pending partial frames`() {
        val assembler = FrameAssembler()
        val pcm = stereoFrame()
        val header = FrameHeader(0, 7, 0, 0, 0, 2, pcm.size)
        val datagram = FrameCodec.encode(header, pcm, 0, Protocol.MAX_PAYLOAD)
        val decoded = FrameCodec.decode(datagram, datagram.size)!!
        assembler.add(decoded.header, datagram, decoded.payloadOffset, decoded.payloadLength)

        assembler.clear()

        val tail = FrameHeader(0, 7, 0, 0, 1, 2, pcm.size)
        val tailDatagram = FrameCodec.encode(tail, pcm, Protocol.MAX_PAYLOAD, pcm.size - Protocol.MAX_PAYLOAD)
        val tailDecoded = FrameCodec.decode(tailDatagram, tailDatagram.size)!!
        // after clear the tail chunk alone cannot complete a frame
        assertNull(assembler.add(tailDecoded.header, tailDatagram, tailDecoded.payloadOffset, tailDecoded.payloadLength))
    }
}
