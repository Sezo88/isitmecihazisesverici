package com.audiobridge.receiver

import android.content.Context
import android.content.res.Resources
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.audiobridge.protocol.Protocol

/**
 * Advertises this phone as an `_audiobridge._udp` receiver so the TV app can find it.
 * TXT records carry the model name for display.
 */
class NsdAdvertiser(context: Context, private val port: Int) {

    private val resources: Resources = context.applicationContext.resources

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java)

    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registered = false

    @Volatile
    private var retried = false

    fun register(onRegistered: (String) -> Unit, onFailed: (String) -> Unit) {
        // Note: do not use `apply` here — inside its scope the name `port` resolves to
        // NsdServiceInfo's own (still unset) port property instead of this class's field.
        val info = NsdServiceInfo()
        info.serviceName = serviceName()
        info.serviceType = Protocol.SERVICE_TYPE
        info.port = port
        info.setAttribute("model", Build.MODEL ?: "Android")
        info.setAttribute("app", "audiobridge")

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered = true
                Log.i(TAG, "mDNS registered as ${serviceInfo.serviceName}:$port")
                onRegistered(serviceInfo.serviceName ?: serviceName())
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                if (!retried) {
                    // Name collision is the usual cause: retry once with a unique suffix.
                    retried = true
                    info.serviceName = serviceName() + "-" + (1000..9999).random()
                    try {
                        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, this)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "retry failed: ${e.message}")
                    }
                }
                onFailed(resources.getString(R.string.error_mdns_failed, errorCode))
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered = false
                Log.i(TAG, "mDNS unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        listener = registrationListener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            onFailed(resources.getString(R.string.error_mdns_error, e.message ?: ""))
        }
    }

    fun unregister() {
        val current = listener ?: return
        listener = null
        if (!registered) return
        try {
            nsdManager.unregisterService(current)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.message}")
        }
        registered = false
    }

    private fun serviceName(): String {
        val model = (Build.MODEL ?: "Android").replace(Regex("[^A-Za-z0-9]"), "")
        return "AudioBridge-" + if (model.isEmpty()) "Receiver" else model
    }

    private companion object {
        const val TAG = "AudioBridgeAdvertise"
    }
}
