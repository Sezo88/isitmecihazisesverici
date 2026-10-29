package com.audiobridge.protocol

import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates the "latency test" click track: a short 1 kHz beep once per second.
 *
 * Frames are produced deterministically from the frame index, so the sender can both
 * transmit them and play them locally on the TV at the same instant. The delay between
 * the TV beep and the hearing-aid beep is the real end-to-end latency of the link.
 */
class ClickTrackGenerator(
    private val sampleRate: Int = Protocol.SAMPLE_RATE,
    private val periodMs: Int = 1000,
    private val clickMs: Int = 40,
    private val frequencyHz: Double = 1000.0,
    private val amplitude: Double = 0.35,
) {
    private val samplesPerFrame = sampleRate * Protocol.FRAME_MS / 1000
    private val periodSamples = sampleRate * periodMs / 1000
    private val clickSamples = sampleRate * clickMs / 1000
    private val fadeSamples = sampleRate * 5 / 1000
    private val bytesPerFrame = samplesPerFrame * Protocol.CHANNELS * 2

    /** Stereo PCM for the given frame index (frame index counts 10 ms steps). */
    fun frame(frameIndex: Long): ByteArray {
        val out = ByteArray(bytesPerFrame)
        val firstSample = frameIndex * samplesPerFrame
        var i = 0
        while (i < samplesPerFrame) {
            val sampleIndex = firstSample + i
            val positionInPeriod = (sampleIndex % periodSamples).toInt()
            val value: Short = if (positionInPeriod < clickSamples) {
                val envelope = envelopeAt(positionInPeriod)
                val angle = 2.0 * PI * frequencyHz * (positionInPeriod.toDouble() / sampleRate)
                (sin(angle) * envelope * amplitude * Short.MAX_VALUE).toInt().toShort()
            } else {
                0
            }
            val offset = i * 4
            Pcm.putSample(out, offset, value)
            Pcm.putSample(out, offset + 2, value)
            i++
        }
        return out
    }

    /** True when this frame contains (the start of) a click, used for UI feedback. */
    fun frameContainsClick(frameIndex: Long): Boolean {
        val firstSample = frameIndex * samplesPerFrame
        return firstSample % periodSamples < clickSamples
    }

    private fun envelopeAt(positionInPeriod: Int): Double {
        if (positionInPeriod < fadeSamples) {
            return positionInPeriod.toDouble() / fadeSamples
        }
        val tailStart = clickSamples - fadeSamples
        if (positionInPeriod > tailStart) {
            return ((clickSamples - positionInPeriod).toDouble() / fadeSamples).coerceIn(0.0, 1.0)
        }
        return 1.0
    }
}
