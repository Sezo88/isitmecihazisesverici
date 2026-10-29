package com.audiobridge.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.audiobridge.protocol.Protocol

/**
 * Stereo 48 kHz PCM output using AudioTrack in low-latency performance mode and
 * stream type MEDIA, so Android routes the audio with normal media semantics and
 * Bluetooth (A2DP / ASHA hearing aids) picks it up the way it picks up any media app.
 */
class AudioOutput {

    private var track: AudioTrack? = null

    var lastError: String = ""
        private set
    var isLowLatency: Boolean = false
        private set
    var bufferSizeBytes: Int = 0
        private set
    var writeErrors: Long = 0
        private set

    fun start(): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            Protocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val minSafe = if (minBuffer > 0) minBuffer else Protocol.FRAME_BYTES_STEREO * 4

        // Prefer a small low-latency buffer; fall back to a normal track if the device
        // rejects the configuration (common on cheap Bluetooth stacks).
        val candidates = listOf(
            maxOf(minSafe, Protocol.FRAME_BYTES_STEREO * 4) to true,   // ~40 ms
            maxOf(minSafe, Protocol.FRAME_BYTES_STEREO * 12) to false, // ~120 ms
        )

        for ((bufferBytes, lowLatency) in candidates) {
            val created = create(bufferBytes, lowLatency) ?: continue
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                continue
            }
            track = created
            isLowLatency = lowLatency
            // AudioTrack exposes the size in frames; stereo 16-bit = 4 bytes per frame.
            bufferSizeBytes = created.bufferSizeInFrames * Protocol.CHANNELS * Protocol.BYTES_PER_SAMPLE
            try {
                created.play()
                if (created.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    Log.i(TAG, "AudioTrack started: buffer=$bufferSizeBytes lowLatency=$lowLatency")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.play failed: ${e.message}")
            }
            created.release()
            track = null
        }

        lastError = "no usable AudioTrack configuration"
        Log.e(TAG, lastError)
        return false
    }

    /** Writes one 10 ms frame. Returns false when the track is gone. */
    fun write(pcm: ByteArray): Boolean {
        val t = track ?: return false
        val written = try {
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            writeErrors++
            Log.w(TAG, "AudioTrack.write failed: ${e.message}")
            return false
        }
        if (written <= 0) {
            writeErrors++
            return false
        }
        return true
    }

    fun stop() {
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun create(bufferBytes: Int, lowLatency: Boolean): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Protocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(
                if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                else AudioTrack.PERFORMANCE_MODE_NONE
            )
            .build()
    } catch (e: Exception) {
        Log.w(TAG, "AudioTrack config rejected (buffer=$bufferBytes lowLatency=$lowLatency): ${e.message}")
        null
    }

    private companion object {
        const val TAG = "AudioBridgeOutput"
    }
}
