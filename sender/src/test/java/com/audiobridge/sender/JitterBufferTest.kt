package com.audiobridge.sender

import com.audiobridge.protocol.JitterBuffer
import com.audiobridge.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private fun frame(seed: Int = 0): ByteArray =
        ByteArray(Protocol.FRAME_BYTES_STEREO) { ((it + seed) and 0x7F).toByte() }

    @Test
    fun `first frame is scheduled target milliseconds after arrival`() {
        val buffer = JitterBuffer(150)
        buffer.onFrame(0, frame(), 1000)

        assertTrue(buffer.isStarted())
        assertEquals(1150L, buffer.nextDueMs(1000))
        // nothing due before the target has elapsed
        assertNull(buffer.takeDue(1148))
        assertNotNull(buffer.takeDue(1151))
    }

    @Test
    fun `frames play out at the 10 ms cadence`() {
        val buffer = JitterBuffer(100)
        buffer.onFrame(0, frame(), 0)
        buffer.onFrame(1, frame(), 10)
        buffer.onFrame(2, frame(), 20)

        // before anything is consumed the next due frame is always seq 0 at t=100
        assertEquals(100L, buffer.nextDueMs(0))
        assertEquals(100L, buffer.nextDueMs(50))
        assertEquals(100L, buffer.nextDueMs(115))

        assertNotNull(buffer.takeDue(105)) // seq 0
        assertEquals(110L, buffer.nextDueMs(106))
        assertNotNull(buffer.takeDue(112)) // seq 1
        assertEquals(120L, buffer.nextDueMs(115))
        assertNotNull(buffer.takeDue(121)) // seq 2
    }

    @Test
    fun `missing frame is concealed with the previous frame`() {
        val buffer = JitterBuffer(50)
        val first = frame(1)
        buffer.onFrame(0, first, 0)
        buffer.onFrame(2, frame(3), 20) // seq 1 is lost

        assertEquals(first, buffer.takeDue(60))       // seq 0
        val concealed = buffer.takeDue(70)            // seq 1 -> repeat of seq 0
        assertEquals(first, concealed)
        val recovered = buffer.takeDue(80)            // seq 2
        assertNotNull(recovered)
        assertFalse(recovered === first)
    }

    @Test
    fun `long loss degrades to silence instead of a stuck frame`() {
        val buffer = JitterBuffer(20)
        buffer.onFrame(0, frame(9), 0)

        var written: ByteArray? = null
        for (i in 1..10) {
            written = buffer.takeDue(10L + i * 10)
        }
        // after a few consecutive misses the buffer must stop repeating old audio
        assertTrue(written!!.all { it == 0.toByte() })
        // 10 calls consume seq 0 (good) plus seqs 1..9 (9 concealed)
        assertEquals(9L, buffer.stats().concealedFrames)
    }

    @Test
    fun `late frames are dropped and counted`() {
        val buffer = JitterBuffer(20)
        buffer.onFrame(0, frame(), 0)
        buffer.onFrame(1, frame(), 10)
        assertNotNull(buffer.takeDue(25)) // seq 0 consumed, next is 1
        buffer.onFrame(1, frame(), 40)    // now late: already scheduled in the past? seq 1 still pending
        buffer.onFrame(0, frame(), 45)    // definitely late
        assertEquals(1L, buffer.stats().lateDropped)
    }

    @Test
    fun `sequence gap larger than one second triggers a resync`() {
        val buffer = JitterBuffer(50)
        buffer.onFrame(0, frame(), 0)
        buffer.onFrame(1, frame(), 10)
        // sender restarts: sequence jumps far ahead
        val jumped = frame(5)
        buffer.onFrame(500, jumped, 2000)

        val stats = buffer.stats()
        assertEquals(3L, stats.receivedFrames)
        assertEquals(498L, stats.lostFrames) // seqs 2..499
        // the next due frame is the resynced one, scheduled 50 ms after arrival
        assertEquals(2050L, buffer.nextDueMs(2000))
        // and it is the jumped-to frame, not the stale ones
        assertTrue(buffer.takeDue(2055) === jumped)
    }

    @Test
    fun `adaptive target grows on loss and decays when stable`() {
        val buffer = JitterBuffer(100)
        buffer.onFrame(0, frame(), 0)
        buffer.onFrame(2, frame(), 20) // loss

        buffer.takeDue(105)
        assertNotNull(buffer.takeDue(115))

        // feed ~9 s of virtual time with a lost frame every ~100 ms:
        // adaptation is rate limited to once per 4 s, so the target must rise
        var now = 120L
        var seq = 3L
        var adapted = false
        repeat(900) { step ->
            if (step % 10 == 0) seq++ // skip one sequence number -> concealed frame
            buffer.onFrame(seq, frame(), now)
            buffer.takeDue(now + 5)
            now += 10
            seq += 1
            if (buffer.targetMs > 100) adapted = true
        }
        assertTrue("target should have grown above the configured value", adapted)
        assertTrue(buffer.targetMs <= 100 + 120)

        // ~30 s of clean stream afterwards: the head-room must decay away
        repeat(3000) {
            buffer.onFrame(seq, frame(), now)
            buffer.takeDue(now + 5)
            now += 10
            seq += 1
        }
        assertEquals("target should decay back to the configured value", 100, buffer.targetMs)
    }

    @Test
    fun `changing the target re-anchors the playout time`() {
        val buffer = JitterBuffer(100)
        buffer.onFrame(0, frame(), 0)
        assertEquals(100L, buffer.nextDueMs(0))

        buffer.setTargetMs(300)
        assertEquals(300L, buffer.nextDueMs(0))

        buffer.setTargetMs(20)
        assertEquals(20L, buffer.nextDueMs(0))
    }

    @Test
    fun `target is clamped to the allowed range`() {
        val buffer = JitterBuffer(5)
        assertEquals(20, buffer.targetMs)
        buffer.setTargetMs(10_000)
        assertEquals(500, buffer.targetMs)
    }

    @Test
    fun `reset clears everything for a new stream`() {
        val buffer = JitterBuffer(100)
        buffer.onFrame(0, frame(), 0)
        buffer.onFrame(1, frame(), 10)
        assertNotNull(buffer.takeDue(105))
        assertTrue(buffer.isStarted())

        buffer.reset()
        assertFalse(buffer.isStarted())
        assertNull(buffer.takeDue(1000))
        val stats = buffer.stats()
        assertEquals(0L, stats.receivedFrames)
        assertEquals(0L, stats.concealedFrames)
    }

    @Test
    fun `loss statistics use sequence gaps`() {
        val buffer = JitterBuffer(50)
        for (seq in longArrayOf(0, 1, 2, 5, 6, 9)) {
            buffer.onFrame(seq, frame(), 10 * seq)
        }
        val stats = buffer.stats()
        assertEquals(6L, stats.receivedFrames)
        assertEquals(4L, stats.lostFrames)
        assertEquals(40.0, stats.lossPercent, 0.01)
    }

    @Test
    fun `buffered frames are capped so memory cannot grow without bound`() {
        val buffer = JitterBuffer(500)
        for (seq in 0L until 1000L) {
            buffer.onFrame(seq, frame(), 0)
        }
        // resync happens on the big jump; with a continuous feed the map is capped at 200 frames
        val stats = buffer.stats()
        assertTrue(stats.bufferedMs <= 200 * Protocol.FRAME_MS)
    }
}
