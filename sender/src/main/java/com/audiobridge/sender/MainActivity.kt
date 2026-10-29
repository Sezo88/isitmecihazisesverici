package com.audiobridge.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * TV interface: starts and stops the stream, shows the status, lets the user pick the
 * receiver either from mDNS discovery or by typing an IP address.
 *
 * Every control is a focusable view, so the D-pad only remote works.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var hintText: TextView
    private lateinit var ipField: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var scanButton: Button
    private lateinit var receiverList: LinearLayout
    private lateinit var receiverEmptyText: TextView
    private lateinit var monoCheck: CheckBox
    private lateinit var clickCheck: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private var discovery: NsdDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var renderedReceiverKeys: List<String> = emptyList()
    private var startAfterPermission = false

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        hintText = findViewById(R.id.hintText)
        ipField = findViewById(R.id.ipField)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        scanButton = findViewById(R.id.scanButton)
        receiverList = findViewById(R.id.receiverList)
        receiverEmptyText = findViewById(R.id.receiverEmptyText)
        monoCheck = findViewById(R.id.monoCheck)
        clickCheck = findViewById(R.id.clickCheck)

        findViewById<TextView>(R.id.footerText).text =
            getString(R.string.footer_help, packageName)

        ipField.setText(prefs.receiverIp)
        monoCheck.isChecked = prefs.mono
        clickCheck.isChecked = prefs.clickTrack

        monoCheck.setOnCheckedChangeListener { _, checked -> prefs.mono = checked }
        clickCheck.setOnCheckedChangeListener { _, checked -> prefs.clickTrack = checked }

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            SenderState.setIdle(getString(R.string.status_stopped))
        }
        scanButton.setOnClickListener { toggleDiscovery() }

        requestOptionalNotificationPermission()
        SenderState.clearReceivers()
        render()
        startButton.requestFocus()
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
        stopDiscovery()
        handler.removeCallbacksAndMessages(null)
    }

    // --- start flow ------------------------------------------------------

    private fun onStartClicked() {
        val target = ipField.text.toString().trim()
        if (target.isEmpty()) {
            SenderState.setError(getString(R.string.status_no_target))
            render()
            ipField.requestFocus()
            return
        }
        prefs.receiverIp = target

        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            startAfterPermission = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        beginStart()
    }

    private fun beginStart() {
        SenderState.setWaiting(getString(R.string.status_requesting_permission))
        SenderState.setHint(null)
        try {
            // Foreground service first: Android 14+ requires the mediaProjection FGS to be
            // running before getMediaProjection() is called.
            startForegroundService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_ARM))
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed", e)
            SenderState.setError(getString(R.string.error_cannot_start_service, e.message ?: ""))
            return
        }
        if (prefs.clickTrack) {
            // Latency test mode generates its own click track: no capture permission needed.
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START))
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Some TV boxes have no MediaProjection permission UI at all. If creating or
        // launching the intent fails, explain the adb workaround instead of crashing.
        val intent = try {
            manager.createScreenCaptureIntent()
        } catch (t: Throwable) {
            Log.e(TAG, "createScreenCaptureIntent failed", t)
            showCaptureHelp(getString(R.string.capture_unavailable_title), getString(R.string.capture_unavailable_message))
            return
        }
        try {
            startActivityForResult(intent, REQUEST_PROJECTION)
        } catch (t: Throwable) {
            Log.e(TAG, "No activity for the capture consent dialog", t)
            showCaptureHelp(getString(R.string.capture_unavailable_title), getString(R.string.capture_unavailable_message))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return

        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, StreamService::class.java)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(StreamService.EXTRA_RESULT_DATA, data)
            startService(intent)
        } else {
            val message = getString(R.string.status_capture_denied)
            startService(
                Intent(this, StreamService::class.java)
                    .setAction(StreamService.ACTION_ABORT)
                    .putExtra(StreamService.EXTRA_MESSAGE, message)
            )
            SenderState.setError(message)
            showCaptureHelp(getString(R.string.capture_denied_title), getString(R.string.capture_denied_message))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        val audioGranted = permissions.indices.any { index ->
            permissions[index] == Manifest.permission.RECORD_AUDIO &&
                grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        }
        if (!audioGranted) {
            startAfterPermission = false
            SenderState.setError(getString(R.string.status_mic_required))
            return
        }
        if (startAfterPermission) {
            startAfterPermission = false
            beginStart()
        }
    }

    private fun showCaptureHelp(title: String, message: String) {
        val adb = getString(R.string.adb_hint, packageName)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$message\n\n$adb")
            .setPositiveButton(R.string.try_again) { _, _ -> requestProjection() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun requestOptionalNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    // --- mDNS discovery --------------------------------------------------

    private fun toggleDiscovery() {
        if (discovery != null) {
            stopDiscovery()
            return
        }

        val wifiManager = getSystemService(WifiManager::class.java)
        val lock = wifiManager.createMulticastLock("audiobridge-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = lock

        SenderState.clearReceivers()
        val nsd = NsdDiscovery(
            context = this,
            onReceiver = { receiver ->
                runOnUiThread {
                    SenderState.addReceiver(receiver)
                    SenderState.setDiscovering(
                        true,
                        getString(R.string.status_found_receiver, receiver.name, receiver.host),
                    )
                    if (ipField.text.isNullOrBlank()) ipField.setText(receiver.host)
                }
            },
            onState = { searching, message ->
                runOnUiThread {
                    SenderState.setDiscovering(searching, message)
                    if (!searching) {
                        // Discovery stopped on its own (error) — release the lock.
                        releaseMulticastLock()
                        discovery = null
                        scanButton.text = getString(R.string.scan_receivers)
                    }
                }
            },
        )
        discovery = nsd
        scanButton.text = getString(R.string.stop_scan)
        nsd.start()
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        releaseMulticastLock()
        scanButton.text = getString(R.string.scan_receivers)
        SenderState.setDiscovering(false, getString(R.string.status_scan_stopped))
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    // --- rendering -------------------------------------------------------

    private fun render() {
        val state = SenderState.snapshot

        statusText.text = state.status.ifEmpty { getString(R.string.idle) }
        detailText.text = state.detail

        val hint = state.hint
        if (hint.isNullOrBlank()) {
            hintText.visibility = View.GONE
        } else {
            hintText.visibility = View.VISIBLE
            hintText.text = hint
        }

        startButton.isEnabled = !state.streaming
        stopButton.isEnabled = state.streaming
        ipField.isEnabled = !state.streaming
        monoCheck.isEnabled = !state.streaming
        clickCheck.isEnabled = !state.streaming

        val keys = state.receivers.map { it.key }
        if (keys != renderedReceiverKeys) {
            renderedReceiverKeys = keys
            renderReceivers(state.receivers)
        }
        receiverEmptyText.visibility =
            if (state.receivers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderReceivers(receivers: List<NsdDiscovery.ReceiverInfo>) {
        receiverList.removeAllViews()
        for (receiver in receivers) {
            val button = Button(this).apply {
                text = getString(R.string.receiver_entry, receiver.host, receiver.port)
                isAllCaps = false
                setBackgroundResource(R.drawable.btn_bg)
                setTextColor(getColor(R.color.text_primary))
                setOnClickListener {
                    ipField.setText("${receiver.host}:${receiver.port}")
                    prefs.receiverIp = "${receiver.host}:${receiver.port}"
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.status_receiver_selected),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                params.bottomMargin = resources.getDimensionPixelSize(R.dimen.gap_small)
                layoutParams = params
            }
            receiverList.addView(button)
        }
    }

    private companion object {
        const val TAG = "AudioBridgeMain"
        const val REQUEST_PROJECTION = 101
        const val REQUEST_PERMISSIONS = 102
        const val REQUEST_NOTIFICATIONS = 103
    }
}
