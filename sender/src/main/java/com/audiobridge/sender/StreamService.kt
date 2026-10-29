package com.audiobridge.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.audiobridge.protocol.Protocol

/**
 * Foreground service that owns the MediaProjection, the audio capture and the UDP socket.
 *
 * Start order matters on Android 14+: a mediaProjection foreground service cannot start
 * before the capture consent (the platform validates the PROJECT_MEDIA app-op), so the
 * activity first arms the service with the always-allowed mediaPlayback type and only
 * upgrades to the mediaProjection type once consent has been granted.
 */
class StreamService : Service() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capture: AudioCapture? = null
    private var clickStream: ClickStream? = null
    private var sender: UdpSender? = null

    private var sequence = 0L
    private var silenceHintShown = false
    private var foregroundStarted = false

    private var lastStatsAt = 0L
    private var lastStatsBytes = 0L
    private var lastStatsPackets = 0L

    private val statsTicker = object : Runnable {
        override fun run() {
            val udp = sender
            if (udp == null) return
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastStatsAt
            if (elapsed >= 1000) {
                val bytes = udp.bytesSent - lastStatsBytes
                val packets = udp.packetsSent - lastStatsPackets
                val kbit = bytes * 8 / elapsed
                SenderState.update {
                    it.copy(
                        detail = buildString {
                            append(getString(R.string.detail_traffic, packets, kbit))
                            if (udp.sendErrors > 0) {
                                append(getString(R.string.detail_send_errors, udp.sendErrors))
                            }
                            if (prefs.clickTrack) append(getString(R.string.detail_latency_suffix))
                            else if (prefs.mono) append(getString(R.string.detail_mono_suffix))
                        }
                    )
                }
                lastStatsAt = now
                lastStatsBytes = udp.bytesSent
                lastStatsPackets = udp.packetsSent
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm()
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                startStreaming(resultCode, data)
            }
            ACTION_STOP -> {
                stopPipeline()
                stopForegroundCompat()
                SenderState.setIdle(getString(R.string.status_stopped))
                stopSelf()
            }
            ACTION_ABORT -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                    ?: getString(R.string.status_capture_denied)
                stopPipeline()
                stopForegroundCompat()
                SenderState.setError(message)
                stopSelf()
            }
            else -> arm()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPipeline()
        stopForegroundCompat()
        super.onDestroy()
    }

    // --- lifecycle -------------------------------------------------------

    private fun arm() {
        if (!startForegroundWith(getString(R.string.notif_waiting), FOREGROUND_TYPE_STREAMING)) return
        SenderState.setWaiting(getString(R.string.status_waiting_permission))
    }

    private fun startStreaming(resultCode: Int, data: Intent?) {
        // Tear down any previous session first. stopPipeline() deliberately leaves the
        // foreground state alone (it is owned by arm()/startForegroundWith) so that the
        // service keeps its notification across a restart.
        stopPipeline()
        if (!foregroundStarted && !startForegroundWith(getString(R.string.notif_starting), FOREGROUND_TYPE_STREAMING)) {
            return
        }

        val target = parseTarget(prefs.receiverIp)
        if (target == null) {
            fail(getString(R.string.error_invalid_address, prefs.receiverIp))
            return
        }

        val udp = try {
            UdpSender(target.first, target.second)
        } catch (e: Exception) {
            fail(getString(R.string.error_cannot_reach, target.first, target.second, e.message ?: ""))
            return
        }
        sender = udp

        lastStatsAt = SystemClock.elapsedRealtime()
        lastStatsBytes = 0
        lastStatsPackets = 0
        silenceHintShown = false
        SenderState.setHint(null)

        // Latency test mode generates its own audio, so it needs no capture
        // permission and works even on boxes where the consent dialog is broken.
        if (prefs.clickTrack) {
            startClickTrack(udp)
            return
        }

        if (data == null) {
            fail(getString(R.string.error_missing_permission_data))
            return
        }

        // Consent is granted at this point, so the mediaProjection service type is allowed.
        if (!upgradeToProjectionForeground()) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            fail(
                getString(
                    R.string.error_projection_rejected,
                    e.message ?: "",
                    appopsCommand(),
                )
            )
            return
        }

        if (mediaProjection == null) {
            fail(getString(R.string.error_projection_null))
            return
        }
        projection = mediaProjection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    if (projection === mediaProjection) {
                        stopPipeline()
                        SenderState.setError(getString(R.string.error_capture_stopped))
                        stopSelf()
                    }
                }
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, handler)

        startCapture(mediaProjection, udp)
    }

    private fun startCapture(mediaProjection: MediaProjection, udp: UdpSender) {
        val mono = prefs.mono
        val flags = if (mono) Protocol.FLAG_MONO else 0
        sequence = 0

        val capture = AudioCapture(
            projection = mediaProjection,
            mono = mono,
            onFrame = { pcm ->
                val timestamp = SystemClock.elapsedRealtime()
                udp.send(sequence++, timestamp, pcm, flags)
            },
            onError = { message ->
                handler.post { fail(getString(R.string.error_capture_generic, message)) }
            },
            onSilence = { handler.post { onSilenceDetected() } },
        )

        if (!capture.start()) {
            capture.stop()
            fail(getString(R.string.error_capture_start))
            return
        }
        this.capture = capture
        markStreaming(udp, mono)
    }

    private fun startClickTrack(udp: UdpSender) {
        val click = ClickStream(udp, localPlayback = true, onError = { message ->
            handler.post { fail(getString(R.string.error_click_track, message)) }
        })
        click.start()
        clickStream = click
        markStreaming(udp, mono = false)
        SenderState.setHint(getString(R.string.hint_latency_test))
    }

    private fun markStreaming(udp: UdpSender, mono: Boolean) {
        val mode = when {
            prefs.clickTrack -> getString(R.string.mode_latency_test)
            mono -> getString(R.string.mode_mono)
            else -> getString(R.string.mode_stereo)
        }
        SenderState.setStreaming(
            status = getString(R.string.status_streaming, udp.target),
            detail = getString(R.string.detail_streaming, mode, Protocol.SAMPLE_RATE / 1000),
        )
        updateNotification(getString(R.string.notif_streaming, udp.target, mode))
        handler.post(statsTicker)
        Log.i(TAG, "Streaming to ${udp.target}, mode=$mode")
    }

    private fun onSilenceDetected() {
        if (silenceHintShown) return
        silenceHintShown = true
        SenderState.setHint(getString(R.string.hint_silence))
        Log.w(TAG, "Only digital silence captured for 10 s — DRM-protected app?")
        updateNotification(getString(R.string.notif_silent))
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        SenderState.setError(message)
        stopPipeline()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopPipeline() {
        handler.removeCallbacks(statsTicker)

        capture?.stop()
        capture = null

        clickStream?.stop()
        clickStream = null

        projectionCallback?.let { callback ->
            try {
                projection?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        sender?.close()
        sender = null

        sequence = 0
        SenderState.update { it.copy(streaming = false) }
    }

    private fun stopForegroundCompat() {
        if (!foregroundStarted) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        foregroundStarted = false
    }

    // --- notification ----------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWith(text: String, type: Int): Boolean {
        val notification = buildNotification(text)
        return try {
            startForeground(NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            true
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when the app is
            // not in the foreground (screen off, app sent to background). Never crash:
            // tell the user to bring the app back and try again.
            Log.e(TAG, "startForeground refused ($type): ${e.message}", e)
            SenderState.setError(getString(R.string.error_fgs_refused))
            stopSelf()
            false
        }
    }

    /** Re-declares the foreground service with the mediaProjection type added. */
    private fun upgradeToProjectionForeground(): Boolean {
        val notification = buildNotification(getString(R.string.notif_start_capture))
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                FOREGROUND_TYPE_STREAMING or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "mediaProjection foreground type rejected: ${e.message}", e)
            fail(getString(R.string.error_service_type, appopsCommand()))
            false
        }
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_stop),
                    getString(R.string.notif_stop),
                    stop,
                ).build()
            )
            .build()
    }

    // --- helpers ---------------------------------------------------------

    private fun appopsCommand(): String =
        "adb shell appops set $packageName PROJECT_MEDIA allow"

    /** Accepts "1.2.3.4", "1.2.3.4:47811" or a hostname. */
    private fun parseTarget(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val host = text.substringBefore(':').trim()
        val portText = if (text.contains(':')) text.substringAfter(':').trim() else ""
        val port = if (portText.isEmpty()) prefs.receiverPort else portText.toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    companion object {
        private const val TAG = "AudioBridgeSender"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 4101

        /** Type used while waiting for consent and for the latency-test-only mode. */
        private const val FOREGROUND_TYPE_STREAMING = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        const val ACTION_ARM = "com.audiobridge.sender.action.ARM"
        const val ACTION_START = "com.audiobridge.sender.action.START"
        const val ACTION_STOP = "com.audiobridge.sender.action.STOP"
        const val ACTION_ABORT = "com.audiobridge.sender.action.ABORT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MESSAGE = "message"
    }
}
