package com.audiobridge.protocol

/**
 * Small adaptive play-out buffer for a continuous 10 ms / 48 kHz stereo PCM stream.
 *
 * The receiver feeds frames in arrival order ([onFrame]) and a play-out thread asks
 * for the next frame to write into AudioTrack ([nextDueMs] / [takeDue]).
 *
 * Timing model
 * ------------
 * The first frame anchors the stream: it is scheduled `targetMs` after its arrival.
 * Every following frame is scheduled one 10 ms frame later, so sequence numbers define
 * the play-out cadence and late/absent frames are concealed instead of stalling.
 *
 * Adaptivity
 * ----------
 * When frames are being lost (concealment) while packets are still flowing, the target
 * is raised in 20 ms steps (up to +120 ms above the configured target). After 15 s
 * without a miss it decays back down in 10 ms steps. The configured target is what the
 * user picks on the slider; the effective target is what is reported in the UI.
 *
 * All times are milliseconds from a monotonic clock (SystemClock.elapsedRealtime).
 * The class is thread safe and does not depend on the Android framework.
 */
class JitterBuffer(
    configuredTargetMs: Int = DEFAULT_TARGET_MS,
    private val maxExtraMs: Int = MAX_EXTRA_MS,
) {

    data class Stats(
        val bufferedMs: Int,
        val targetMs: Int,
        val configuredTargetMs: Int,
        val receivedFrames: Long,
        val lostFrames: Long,
        val lateDropped: Long,
        val concealedFrames: Long,
        val jitterMs: Int,
        val playing: Boolean,
    ) {
        val lossPercent: Double
            get() {
                val total = receivedFrames + lostFrames
                return if (total <= 0) 0.0 else 100.0 * lostFrames / total
            }
    }

    private val lock = Any()
    private val frames = java.util.TreeMap<Long, ByteArray>()

    private var baseTargetMs: Int = configuredTargetMs.coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
    private var extraMs: Int = 0

    private var started: Boolean = false
    private var anchorSeq: Long = 0
    private var anchorWallMs: Long = 0
    private var nextSeq: Long = 0

    private var lastGood: ByteArray? = null
    private var consecutiveMisses: Int = 0

    private var receivedFrames: Long = 0
    private var lostFrames: Long = 0
    private var lateDropped: Long = 0
    private var concealedFrames: Long = 0
    private var maxSeqSeen: Long = -1

    private var jitterMs: Double = 0.0
    private var lastArrivalMs: Long = -1
    private var lastPacketAtMs: Long = 0
    private var lastAdaptAtMs: Long = 0
    private var lastMissAtMs: Long = 0
    private var lastDecayAtMs: Long = 0

    private val silence = ByteArray(Protocol.FRAME_BYTES_STEREO)

    val configuredTargetMs: Int
        get() = synchronized(lock) { baseTargetMs }

    /** Effective target, configured value plus adaptive head-room. */
    val targetMs: Int
        get() = synchronized(lock) { effectiveTargetMs() }

    fun isStarted(): Boolean = synchronized(lock) { started }

    /**
     * Milliseconds since the last frame arrived, or -1 when nothing has arrived since the
     * last reset. The receiver uses this to notice that the sender stopped and to leave
     * play-out instead of concealing forever.
     */
    fun idleForMs(nowMs: Long): Long = synchronized(lock) {
        if (lastPacketAtMs <= 0) -1 else nowMs - lastPacketAtMs
    }

    /**
     * Adds a complete stereo frame. Returns false when the frame was too late to be useful.
     * The frame bytes are owned by the buffer afterwards.
     */
    fun onFrame(sequence: Long, pcm: ByteArray, arrivalMs: Long): Boolean = synchronized(lock) {
        receivedFrames++
        lastPacketAtMs = arrivalMs

        if (lastArrivalMs >= 0 && sequence == maxSeqSeen + 1) {
            val delta = (arrivalMs - lastArrivalMs).toDouble()
            val deviation = kotlin.math.abs(delta - Protocol.FRAME_MS)
            jitterMs = jitterMs * 0.9 + deviation * 0.1
        }
        lastArrivalMs = arrivalMs

        if (sequence > maxSeqSeen) {
            if (maxSeqSeen >= 0 && sequence > maxSeqSeen + 1) {
                lostFrames += sequence - maxSeqSeen - 1
            }
            maxSeqSeen = sequence
        }

        if (!started) {
            started = true
            anchorSeq = sequence
            nextSeq = sequence
            anchorWallMs = arrivalMs + effectiveTargetMs()
        } else if (sequence > nextSeq + RESYNC_FRAMES) {
            // The stream jumped forward by more than a second: the sender restarted or
            // the network stalled. Re-anchor instead of concealing through the gap.
            frames.clear()
            anchorSeq = sequence
            nextSeq = sequence
            anchorWallMs = arrivalMs + effectiveTargetMs()
            consecutiveMisses = 0
            lastGood = null
        }

        if (sequence < nextSeq) {
            lateDropped++
            return@synchronized false
        }

        frames[sequence] = pcm
        while (frames.size > MAX_PENDING_FRAMES) frames.pollFirstEntry()
        true
    }

    /** Absolute time (ms, same clock as [onFrame]) at which the next frame is due. */
    fun nextDueMs(nowMs: Long): Long = synchronized(lock) {
        if (!started) return@synchronized nowMs + Protocol.FRAME_MS
        dueTimeOf(nextSeq)
    }

    /**
     * Returns the PCM to write now, or null when nothing is due yet.
     * Missing frames are concealed by repeating the last good frame for a few frames
     * and then by silence, so the stream never stalls.
     */
    fun takeDue(nowMs: Long): ByteArray? = synchronized(lock) {
        if (!started) return@synchronized null
        if (nowMs + 1 < dueTimeOf(nextSeq)) return@synchronized null

        val sequence = nextSeq
        nextSeq++

        val frame = frames.remove(sequence)
        if (frame != null) {
            consecutiveMisses = 0
            lastGood = frame
            decayIfStable(nowMs)
            return@synchronized frame
        }

        concealedFrames++
        consecutiveMisses++
        lastMissAtMs = nowMs
        adaptOnMiss(nowMs)
        if (consecutiveMisses >= CONCEAL_REPEAT_FRAMES) lastGood = null
        lastGood ?: silence
    }

    /** Changes the user configured target. Takes effect for the frames that are still buffered. */
    fun setTargetMs(ms: Int) = synchronized(lock) {
        val clamped = ms.coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
        val before = effectiveTargetMs()
        baseTargetMs = clamped
        val after = effectiveTargetMs()
        if (started && before != after) anchorWallMs += (after - before).toLong()
    }

    /** Drops all state, used when a new stream id appears. */
    fun reset() = synchronized(lock) {
        frames.clear()
        started = false
        anchorSeq = 0
        anchorWallMs = 0
        nextSeq = 0
        lastGood = null
        consecutiveMisses = 0
        receivedFrames = 0
        lostFrames = 0
        lateDropped = 0
        concealedFrames = 0
        maxSeqSeen = -1
        jitterMs = 0.0
        lastArrivalMs = -1
        lastPacketAtMs = 0
        lastAdaptAtMs = 0
        lastMissAtMs = 0
        lastDecayAtMs = 0
        extraMs = 0
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            bufferedMs = frames.size * Protocol.FRAME_MS,
            targetMs = effectiveTargetMs(),
            configuredTargetMs = baseTargetMs,
            receivedFrames = receivedFrames,
            lostFrames = lostFrames,
            lateDropped = lateDropped,
            concealedFrames = concealedFrames,
            jitterMs = kotlin.math.round(jitterMs).toInt(),
            playing = started,
        )
    }

    // --- internals -------------------------------------------------------

    private fun effectiveTargetMs(): Int =
        (baseTargetMs + extraMs).coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)

    private fun dueTimeOf(sequence: Long): Long =
        anchorWallMs + (sequence - anchorSeq) * Protocol.FRAME_MS

    private fun adaptOnMiss(nowMs: Long) {
        val packetsFlowing = nowMs - lastPacketAtMs < 1000
        if (!packetsFlowing) return
        if (extraMs >= maxExtraMs) return
        if (nowMs - lastAdaptAtMs < ADAPT_INTERVAL_MS) return

        extraMs = minOf(maxExtraMs, extraMs + ADAPT_STEP_MS)
        anchorWallMs += ADAPT_STEP_MS            // give the buffer more room
        lastAdaptAtMs = nowMs
    }

    private fun decayIfStable(nowMs: Long) {
        if (extraMs <= 0) return
        if (nowMs - lastMissAtMs < STABLE_PERIOD_MS) return
        if (nowMs - lastDecayAtMs < DECAY_INTERVAL_MS) return
        extraMs -= DECAY_STEP_MS
        anchorWallMs -= DECAY_STEP_MS
        lastDecayAtMs = nowMs
    }

    companion object {
        const val MIN_TARGET_MS = 20
        const val MAX_TARGET_MS = 500
        const val DEFAULT_TARGET_MS = 150
        const val MAX_EXTRA_MS = 120

        private const val ADAPT_STEP_MS = 20
        private const val ADAPT_INTERVAL_MS = 4000L
        private const val DECAY_STEP_MS = 10
        private const val DECAY_INTERVAL_MS = 2000L
        private const val STABLE_PERIOD_MS = 15_000L
        private const val CONCEAL_REPEAT_FRAMES = 3
        private const val RESYNC_FRAMES = 100          // 1 second
        private const val MAX_PENDING_FRAMES = 200     // 2 seconds hard cap
    }
}
