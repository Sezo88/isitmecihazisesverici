package com.audiobridge.sender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.audiobridge.protocol.Pcm
import com.audiobridge.protocol.Protocol

/**
 * Captures the TV's system audio through [MediaProjection] + [AudioPlaybackCaptureConfiguration]
 * and emits 10 ms PCM frames.
 *
 * Captured audio is the playback mix of other apps (usage MEDIA, GAME, UNKNOWN). Apps that set
 * ALLOW_CAPTURE_BY_NONE (most DRM video players) are excluded by the platform and produce silence;
 * [onSilence] fires after 10 s of digital silence so the UI can explain that.
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val mono: Boolean,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onSilence: () -> Unit,
) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // Reused buffers: onFrame() must consume synchronously and must not retain them.
    private val frame = ByteArray(Protocol.FRAME_BYTES_STEREO)
    private val monoFrame = ByteArray(Protocol.FRAME_BYTES_MONO)

    fun start(): Boolean {
        val configuration = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (e: Exception) {
            onError("Cannot build playback capture config: ${e.message}")
            return false
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Protocol.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            Protocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(
            if (minBuffer > 0) minBuffer * 2 else 0,
            Protocol.FRAME_BYTES_STEREO * 8,
        )

        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(configuration)
                .build()
        } catch (e: SecurityException) {
            onError("RECORD_AUDIO permission missing: ${e.message}")
            return false
        } catch (e: Exception) {
            onError("AudioRecord could not be created: ${e.message}")
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("AudioRecord failed to initialize (state=${audioRecord.state})")
            return false
        }

        record = audioRecord
        running = true
        thread = Thread({ captureLoop(audioRecord) }, "sender-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Capture started: ${Protocol.SAMPLE_RATE} Hz, 16 bit, stereo, 10 ms frames, mono=$mono")
        return true
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        thread?.join(700)
        thread = null
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        Log.i(TAG, "Capture stopped")
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        var silenceFrames = 0
        var silenceHintSent = false
        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("AudioRecord.startRecording() did not start (permission or capture policy?)")
                return
            }

            while (running) {
                var filled = 0
                while (filled < frame.size && running) {
                    val read = audioRecord.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                    if (read < 0) {
                        onError("AudioRecord.read failed: $read")
                        return
                    }
                    if (read == 0) continue
                    filled += read
                }
                if (!running) break

                val peak = Pcm.peakAbsolute(frame)
                if (peak <= SILENCE_PEAK) {
                    silenceFrames++
                } else {
                    silenceFrames = 0
                    silenceHintSent = false
                }
                if (!silenceHintSent && silenceFrames >= SILENCE_FRAMES) {
                    silenceHintSent = true
                    onSilence()
                }

                if (mono) {
                    Pcm.downmixStereoToMono(frame, monoFrame)
                    onFrame(monoFrame)
                } else {
                    onFrame(frame)
                }
            }
        } catch (e: Exception) {
            if (running) onError("Capture loop failed: ${e.message}")
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val TAG = "AudioBridgeCapture"

        /** Digital silence is exactly 0; a tiny threshold also catches dither/noise floors. */
        const val SILENCE_PEAK = 8
        const val SILENCE_FRAMES = 1000 // 1000 * 10 ms = 10 s
    }
}
