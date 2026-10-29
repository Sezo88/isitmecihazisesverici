package com.audiobridge.protocol

/**
 * Helpers for 16-bit little-endian PCM manipulation. All byte arrays are interleaved
 * 16-bit little-endian samples; stereo is L,R,L,R,...
 */
object Pcm {

    /** Duplicates a mono frame to stereo (L = R = mono sample). */
    fun monoToStereo(mono: ByteArray): ByteArray {
        val samples = mono.size / 2
        val out = ByteArray(samples * 4)
        var src = 0
        var dst = 0
        while (src + 1 < mono.size) {
            val lo = mono[src]
            val hi = mono[src + 1]
            out[dst] = lo
            out[dst + 1] = hi
            out[dst + 2] = lo
            out[dst + 3] = hi
            src += 2
            dst += 4
        }
        return out
    }

    /** Averages L and R into a mono frame. [output] must hold [stereo].size / 2 bytes. */
    fun downmixStereoToMono(stereo: ByteArray, output: ByteArray) {
        val samples = stereo.size / 4
        var out = 0
        var i = 0
        while (i + 3 < stereo.size && out + 1 < output.size) {
            val left = sampleAt(stereo, i)
            val right = sampleAt(stereo, i + 2)
            val mixed = ((left + right) / 2).toShort()
            output[out] = (mixed.toInt() and 0xFF).toByte()
            output[out + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
            i += 4
            out += 2
        }
        // Zero anything the loop did not fill (defensive, normally exact).
        while (out < output.size) {
            output[out] = 0
            out++
        }
        if (samples == 0) output.fill(0)
    }

    /** Largest absolute sample value, used for silence detection. */
    fun peakAbsolute(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val value = sampleAt(pcm, i).toInt()
            val magnitude = if (value == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(value)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    fun sampleAt(pcm: ByteArray, byteOffset: Int): Short =
        (((pcm[byteOffset].toInt() and 0xFF) or ((pcm[byteOffset + 1].toInt() and 0xFF) shl 8)).toShort())

    fun putSample(pcm: ByteArray, byteOffset: Int, value: Short) {
        pcm[byteOffset] = (value.toInt() and 0xFF).toByte()
        pcm[byteOffset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}
