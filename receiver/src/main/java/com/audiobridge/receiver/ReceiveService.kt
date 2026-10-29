package com.audiobridge.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.audiobridge.protocol.FrameAssembler
import com.audiobridge.protocol.FrameCodec
import com.audiobridge.protocol.JitterBuffer
import com.audiobridge.protocol.Pcm
import com.audiobridge.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Foreground media-playback service: receives UDP datagrams, reassembles 10 ms frames,
 * runs them through the adaptive jitter buffer and writes them to AudioTrack.
 *
 * Holds a partial wake lock and a Wi-Fi lock so playback survives the screen turning off.
 */
class ReceiveService : Service() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var socket: DatagramSocket? = null
    private var audioOutput: AudioOutput? = null
    private var jitter: JitterBuffer? = null
    private var receiverThread: Thread? = null
    private var writerThread: Thread? = null
    private var advertiser: NsdAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var running = false

    @Volatile
    private var peerAddress: String? = null

    private var udpPort: Int = Protocol.DEFAULT_PORT
    private var localAddress: String = ""
    private var foregroundStarted = false
    private var statLogCounter = 0

    private val statsTicker = object : Runnable {
        override fun run() {
            publishStats()
            if (running) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> {
                stopListening()
                ReceiverState.setIdle(getString(R.string.detail_stopped))
                stopSelf()
            }
            ACTION_SET_TARGET -> {
                val target = intent.getIntExtra(EXTRA_TARGET_MS, -1)
                if (target > 0) applyTarget(target)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    // --- lifecycle -------------------------------------------------------

    private fun startListening() {
        if (running) return
        startForegroundCompat(getString(R.string.notif_starting))
        if (!foregroundStarted) return

        val output = AudioOutput()
        if (!output.start()) {
            fail(getString(R.string.error_audio_start, output.lastError))
            return
        }
        audioOutput = output

        val buffer = JitterBuffer(prefs.bufferMs)
        jitter = buffer

        val bound = bindSocket()
        if (bound == null) {
            fail(getString(R.string.error_bind, prefs.port))
            return
        }
        socket = bound
        udpPort = bound.localPort
        localAddress = localIpv4()

        acquireLocks()
        running = true
        peerAddress = null

        receiverThread = Thread({ receiveLoop(bound) }, "udp-receiver").apply { start() }
        writerThread = Thread({ writerLoop() }, "audio-writer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        advertiser = NsdAdvertiser(this, udpPort).also { nsd ->
            nsd.register(
                onRegistered = { name ->
                    Log.i(TAG, "Announced as $name")
                    ReceiverState.update { it.copy(detail = getString(R.string.detail_announced, name)) }
                    updateNotification(getString(R.string.notif_listening, "$localAddress:$udpPort"))
                },
                onFailed = { message ->
                    Log.w(TAG, message)
                    ReceiverState.update {
                        it.copy(detail = getString(R.string.detail_manual_ip, message, localAddress, udpPort))
                    }
                },
            )
        }

        ReceiverState.update {
            it.copy(
                listening = true,
                phase = getString(R.string.phase_listening),
                detail = getString(R.string.detail_waiting),
                localAddress = localAddress,
                port = udpPort,
                targetMs = buffer.targetMs,
                configuredTargetMs = buffer.configuredTargetMs,
                error = null,
            )
        }
        updateNotification(getString(R.string.notif_listening, "$localAddress:$udpPort"))
        handler.post(statsTicker)
        Log.i(TAG, "Listening on $localAddress:$udpPort (buffer ${buffer.targetMs} ms)")
    }

    private fun stopListening() {
        val wasRunning = running
        running = false
        handler.removeCallbacks(statsTicker)

        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null

        receiverThread?.join(600)
        receiverThread = null

        audioOutput?.stop()
        audioOutput = null

        writerThread?.join(600)
        writerThread = null

        advertiser?.unregister()
        advertiser = null

        releaseLocks()
        jitter = null
        peerAddress = null

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (wasRunning) ReceiverState.update { it.copy(listening = false, bufferMs = 0) }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        stopListening()
        ReceiverState.setError(message)
        stopSelf()
    }

    private fun applyTarget(targetMs: Int) {
        prefs.bufferMs = targetMs
        jitter?.setTargetMs(targetMs)
        ReceiverState.update {
            it.copy(targetMs = jitter?.targetMs ?: targetMs, configuredTargetMs = targetMs)
        }
        Log.i(TAG, "Buffer target set to $targetMs ms")
    }

    // --- UDP -------------------------------------------------------------

    private fun bindSocket(): DatagramSocket? {
        for (port in listOf(prefs.port, 0)) {
            try {
                val candidate = DatagramSocket(null)
                candidate.reuseAddress = true
                try {
                    candidate.receiveBufferSize = 1 shl 20
                } catch (_: Exception) {
                }
                candidate.bind(InetSocketAddress(port))
                Log.i(TAG, "UDP bound to port ${candidate.localPort}")
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "Cannot bind UDP port $port: ${e.message}")
            }
        }
        return null
    }

    private fun receiveLoop(datagramSocket: DatagramSocket) {
        val buffer = ByteArray(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD)
        val assembler = FrameAssembler()
        val bufferRef = jitter ?: return
        var currentStream = Int.MIN_VALUE

        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                datagramSocket.receive(packet)
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "UDP receive failed: ${e.message}")
                Thread.sleep(20)
                continue
            }

            val decoded = FrameCodec.decode(buffer, packet.length) ?: continue
            val header = decoded.header

            if (header.streamId != currentStream) {
                currentStream = header.streamId
                assembler.clear()
                bufferRef.reset()
                peerAddress = packet.address?.hostAddress
                Log.i(TAG, "New stream ${header.streamId} from $peerAddress")
            }
            peerAddress = packet.address?.hostAddress ?: peerAddress

            val frame = assembler.add(header, buffer, decoded.payloadOffset, decoded.payloadLength) ?: continue
            val pcm = if (frame.isMono) Pcm.monoToStereo(frame.pcm) else frame.pcm
            bufferRef.onFrame(frame.sequence, pcm, SystemClock.elapsedRealtime())
        }
    }

    private fun writerLoop() {
        val bufferRef = jitter ?: return
        val output = audioOutput ?: return

        while (running) {
            val now = SystemClock.elapsedRealtime()

            // The sender stopped (or went away): leave play-out instead of writing
            // concealment forever.
            val idle = bufferRef.idleForMs(now)
            if (bufferRef.isStarted() && idle > STREAM_IDLE_RESET_MS) {
                Log.i(TAG, "No packets for $idle ms — resetting play-out")
                bufferRef.reset()
                ReceiverState.update {
                    it.copy(
                        phase = getString(R.string.phase_listening),
                        detail = getString(R.string.detail_waiting),
                        bufferMs = 0,
                    )
                }
            }

            if (!bufferRef.isStarted()) {
                Thread.sleep(20)
                continue
            }
            val due = bufferRef.nextDueMs(now)
            if (now < due) {
                Thread.sleep((due - now).coerceIn(1, 10))
                continue
            }
            val pcm = bufferRef.takeDue(now) ?: continue
            if (!output.write(pcm)) {
                if (!running) break
                // A single failed write is transient (e.g. buffer reset); keep going.
                Thread.sleep(2)
            }
        }
    }

    // --- stats / notification -------------------------------------------

    private fun publishStats() {
        val bufferRef = jitter ?: return
        val stats = bufferRef.stats()
        val output = audioOutput

        // Periodic log line: useful when diagnosing on real hardware via
        // `adb logcat -s AudioBridgeReceiver`.
        statLogCounter++
        if (statLogCounter >= 10) {
            statLogCounter = 0
            Log.i(
                TAG,
                "stats: buffer=${stats.bufferedMs} ms target=${stats.targetMs} ms " +
                    "jitter=${stats.jitterMs} ms loss=${"%.2f".format(stats.lossPercent)}% " +
                    "received=${stats.receivedFrames} lost=${stats.lostFrames} " +
                    "concealed=${stats.concealedFrames} late=${stats.lateDropped} peer=$peerAddress"
            )
        }

        ReceiverState.update {
            it.copy(
                listening = running,
                phase = when {
                    !running -> getString(R.string.idle)
                    stats.playing -> getString(R.string.phase_playing)
                    else -> getString(R.string.phase_listening)
                },
                detail = when {
                    stats.playing && stats.lossPercent < 1.0 -> getString(R.string.detail_smooth)
                    stats.playing -> getString(R.string.detail_lossy)
                    else -> getString(R.string.detail_waiting)
                },
                peer = peerAddress,
                localAddress = localAddress,
                port = udpPort,
                bufferMs = stats.bufferedMs,
                targetMs = stats.targetMs,
                configuredTargetMs = stats.configuredTargetMs,
                lossPercent = stats.lossPercent,
                jitterMs = stats.jitterMs,
                receivedFrames = stats.receivedFrames,
                lostFrames = stats.lostFrames,
                concealedFrames = stats.concealedFrames,
                audioInfo = when {
                    output == null -> ""
                    output.isLowLatency ->
                        getString(R.string.audio_low_latency, output.bufferSizeBytes / 192)
                    else -> getString(R.string.audio_normal, output.bufferSizeBytes / 192)
                },
            )
        }
    }

    // --- locks -----------------------------------------------------------

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioBridge::Playback",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: ${e.message}")
        }

        try {
            val wifiManager = getSystemService(WifiManager::class.java)
            val mode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            wifiLock = wifiManager.createWifiLock(mode, "AudioBridge::Wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
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

    private fun startForegroundCompat(text: String) {
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            foregroundStarted = true
        } catch (e: Exception) {
            // Android 12+ refuses this when the app is not in the foreground. Report it
            // instead of crashing the process.
            Log.e(TAG, "startForeground refused: ${e.message}", e)
            ReceiverState.setError(getString(R.string.error_fgs_refused))
            stopSelf()
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
            Intent(this, ReceiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ear)
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

    private fun localIpv4(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
            ?: "0.0.0.0"
    } catch (e: Exception) {
        "0.0.0.0"
    }

    companion object {
        private const val TAG = "AudioBridgeReceiver"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_START = "com.audiobridge.receiver.action.START"
        const val ACTION_STOP = "com.audiobridge.receiver.action.STOP"
        const val ACTION_SET_TARGET = "com.audiobridge.receiver.action.SET_TARGET"
        const val EXTRA_TARGET_MS = "target_ms"

        /** Leave play-out after this long without packets. */
        private const val STREAM_IDLE_RESET_MS = 2000L
    }
}
