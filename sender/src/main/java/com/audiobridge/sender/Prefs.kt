package com.audiobridge.sender

import android.content.Context
import android.content.SharedPreferences
import com.audiobridge.protocol.Protocol

/** Persisted sender settings. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sender_prefs", Context.MODE_PRIVATE)

    var receiverIp: String
        get() = prefs.getString(KEY_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var receiverPort: Int
        get() = prefs.getInt(KEY_PORT, Protocol.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var mono: Boolean
        get() = prefs.getBoolean(KEY_MONO, false)
        set(value) = prefs.edit().putBoolean(KEY_MONO, value).apply()

    var clickTrack: Boolean
        get() = prefs.getBoolean(KEY_CLICK, false)
        set(value) = prefs.edit().putBoolean(KEY_CLICK, value).apply()

    private companion object {
        const val KEY_IP = "receiver_ip"
        const val KEY_PORT = "receiver_port"
        const val KEY_MONO = "mono"
        const val KEY_CLICK = "click_track"
    }
}
