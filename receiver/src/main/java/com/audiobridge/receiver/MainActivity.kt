package com.audiobridge.receiver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView

/**
 * Phone UI: start/stop listening, buffer size, live statistics.
 * Playback goes through normal Android media routing, so a paired ASHA
 * hearing aid pair receives it like any other media audio.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var statsText: TextView
    private lateinit var bufferLabel: TextView
    private lateinit var bufferSeek: SeekBar
    private lateinit var keepScreenCheck: CheckBox
    private lateinit var listenButton: Button
    private lateinit var addressText: TextView
    private lateinit var hintText: TextView
    private lateinit var footerText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        statsText = findViewById(R.id.statsText)
        bufferLabel = findViewById(R.id.bufferLabel)
        bufferSeek = findViewById(R.id.bufferSeek)
        keepScreenCheck = findViewById(R.id.keepScreenCheck)
        listenButton = findViewById(R.id.listenButton)
        addressText = findViewById(R.id.addressText)
        hintText = findViewById(R.id.hintText)
        footerText = findViewById(R.id.footerText)

        footerText.text = getString(R.string.footer_help)

        bufferSeek.max = (MAX_BUFFER_MS - MIN_BUFFER_MS) / BUFFER_STEP_MS
        bufferSeek.progress = ((prefs.bufferMs - MIN_BUFFER_MS) / BUFFER_STEP_MS)
            .coerceIn(0, bufferSeek.max)
        updateBufferLabel()

        bufferSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = MIN_BUFFER_MS + progress * BUFFER_STEP_MS
                prefs.bufferMs = value
                updateBufferLabel()
                sendServiceCommand(ReceiveService.ACTION_SET_TARGET) { it.putExtra(ReceiveService.EXTRA_TARGET_MS, value) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        keepScreenCheck.isChecked = prefs.keepScreenOn
        applyKeepScreenOn()
        keepScreenCheck.setOnCheckedChangeListener { _, checked ->
            prefs.keepScreenOn = checked
            applyKeepScreenOn()
        }

        listenButton.setOnClickListener {
            val state = ReceiverState.snapshot
            if (state.listening) {
                sendServiceCommand(ReceiveService.ACTION_STOP)
                ReceiverState.setIdle(getString(R.string.detail_stopped))
            } else {
                startListening()
            }
            render()
        }

        requestNotificationPermission()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startListening() {
        val intent = Intent(this, ReceiveService::class.java).setAction(ReceiveService.ACTION_START)
        try {
            startForegroundService(intent)
            ReceiverState.update {
                it.copy(listening = true, phase = getString(R.string.phase_starting), error = null)
            }
        } catch (e: Exception) {
            ReceiverState.setError(getString(R.string.error_cannot_start_service, e.message ?: ""))
        }
    }

    private fun sendServiceCommand(action: String, extras: (Intent) -> Unit = {}) {
        val intent = Intent(this, ReceiveService::class.java).setAction(action)
        extras(intent)
        try {
            // The service is already in the foreground while listening; plain startService is enough.
            startService(intent)
        } catch (e: Exception) {
            // Service not running (e.g. slider moved while stopped) — nothing to do.
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateBufferLabel() {
        bufferLabel.text = getString(R.string.buffer_value, prefs.bufferMs)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun render() {
        val state = ReceiverState.snapshot

        statusText.text = when {
            state.error != null -> getString(R.string.phase_error)
            state.phase.isNotEmpty() -> state.phase
            else -> getString(R.string.idle)
        }
        statusText.setTextColor(
            getColor(if (state.error != null) R.color.error else R.color.text_primary)
        )

        detailText.text = state.error ?: state.detail
        listenButton.text = getString(
            if (state.listening) R.string.stop_listening else R.string.start_listening
        )

        val peer = state.peer
        val addressLines = buildString {
            if (state.localAddress.isNotEmpty() && state.port > 0) {
                append(getString(R.string.this_phone, state.localAddress, state.port))
            }
            if (peer != null) {
                if (isNotEmpty()) append('\n')
                append(getString(R.string.sender_address, peer))
            }
        }
        addressText.text = addressLines
        addressText.visibility = if (addressLines.isEmpty()) View.GONE else View.VISIBLE

        if (state.listening || state.receivedFrames > 0) {
            val bufferLine = getString(
                R.string.stats_buffer,
                state.bufferMs,
                state.targetMs,
                state.jitterMs,
            )
            val lossLine = getString(
                R.string.stats_loss,
                state.lossPercent,
                state.concealedFrames,
            )
            val receivedLine = getString(R.string.stats_received, state.receivedFrames, state.lostFrames)
            val audioLine = if (state.audioInfo.isEmpty()) "" else "\n${state.audioInfo}"
            statsText.text = bufferLine + "\n" + lossLine + "\n" + receivedLine + audioLine
            statsText.visibility = View.VISIBLE
        } else {
            statsText.visibility = View.GONE
        }

        hintText.text = getString(R.string.hint_body)
        hintText.visibility =
            if (state.listening && state.peer == null) View.VISIBLE else View.GONE
    }

    private companion object {
        const val MIN_BUFFER_MS = 20
        const val MAX_BUFFER_MS = 500
        const val BUFFER_STEP_MS = 10
        const val REQUEST_NOTIFICATIONS = 201
    }
}
