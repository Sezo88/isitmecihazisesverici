package com.audiobridge.receiver

import android.content.Context
import android.content.SharedPreferences
import com.audiobridge.protocol.JitterBuffer
import com.audiobridge.protocol.Protocol

/** Persisted receiver settings. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE)

    var bufferMs: Int
        get() = prefs.getInt(KEY_BUFFER, JitterBuffer.DEFAULT_TARGET_MS)
            .coerceIn(JitterBuffer.MIN_TARGET_MS, JitterBuffer.MAX_TARGET_MS)
        set(value) = prefs.edit()
            .putInt(KEY_BUFFER, value.coerceIn(JitterBuffer.MIN_TARGET_MS, JitterBuffer.MAX_TARGET_MS))
            .apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, Protocol.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    private companion object {
        const val KEY_BUFFER = "buffer_ms"
        const val KEY_KEEP_SCREEN = "keep_screen_on"
        const val KEY_PORT = "port"
    }
}
