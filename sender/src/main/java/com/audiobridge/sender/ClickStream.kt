package com.audiobridge.sender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.audiobridge.protocol.ClickTrackGenerator
import com.audiobridge.protocol.Protocol
import java.util.concurrent.locks.LockSupport

/**
 * Latency test mode.
 *
 * Instead of capturing audio, generate a 1 kHz click once per second and
 *  - play it on the TV's own output, and
 *  - send exactly the same audio through the network.
 *
 * The listener hears the TV click and then the same click in the hearing aids; the gap
 * between them is the real end-to-end latency (network + jitter buffer + AudioTrack +
 * Bluetooth). Both are driven from one 10 ms clock so the stream stays perfectly paced.
 */
class ClickStream(
    private val sender: UdpSender,
    private val localPlayback: Boolean = true,
    private val onError: (String) -> Unit = {},
) {

    private val generator = ClickTrackGenerator()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var localWriteFailures = 0

    @Volatile
    private var running = false

    var clicksSent: Long = 0
        private set

    fun start() {
        if (localPlayback) {
            track = createTrack()
            try {
                track?.play()
            } catch (e: Exception) {
                Log.w(TAG, "Local click playback could not start: ${e.message}")
                try {
                    track?.release()
                } catch (_: Exception) {
                }
                track = null
            }
        }
        running = true
        thread = Thread({ loop() }, "click-track").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Latency test mode started (local playback=${track != null})")
    }

    fun stop() {
        running = false
        thread?.join(700)
        thread = null
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

    private fun loop() {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val startMs = startNs / 1_000_000L
        var frameIndex = 0L
        try {
            while (running) {
                val pcm = generator.frame(frameIndex)
                val timestamp = startMs + frameIndex * Protocol.FRAME_MS
                sender.send(frameIndex, timestamp, pcm, Protocol.FLAG_CLICK_TRACK)
                if (generator.frameContainsClick(frameIndex)) clicksSent++

                track?.let { t ->
                    try {
                        // Never block the network stream on local monitoring: if the TV's
                        // audio sink stalls, drop the local copy instead of stalling audio.
                        val written = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) localWriteFailures++
                    } catch (_: Exception) {
                        localWriteFailures++
                    }
                    if (localWriteFailures > MAX_LOCAL_WRITE_FAILURES) {
                        // Give up on the reference beep but keep streaming.
                        Log.w(TAG, "Disabling local click playback after repeated write failures")
                        try {
                            t.stop()
                            t.release()
                        } catch (_: Exception) {
                        }
                        track = null
                    }
                }

                frameIndex++
                waitUntil(startNs + frameIndex * Protocol.FRAME_MS * 1_000_000L)
            }
        } catch (e: Exception) {
            if (running) onError("Click track failed: ${e.message}")
        }
    }

    /** Sleep in short slices so stop() is responsive. */
    private fun waitUntil(targetNs: Long) {
        while (running) {
            val remaining = targetNs - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0) return
            LockSupport.parkNanos(minOf(remaining, 2_000_000L))
        }
    }

    private fun createTrack(): AudioTrack? = try {
        val minBuffer = AudioTrack.getMinBufferSize(
            Protocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(if (minBuffer > 0) minBuffer else 0, Protocol.FRAME_BYTES_STEREO * 4)
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
            .build()
    } catch (e: Exception) {
        Log.w(TAG, "Local click playback unavailable: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "AudioBridgeClick"
        const val MAX_LOCAL_WRITE_FAILURES = 25
    }
}
