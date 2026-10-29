package com.audiobridge.sender

import android.content.Context
import android.content.res.Resources
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.audiobridge.protocol.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds receivers that advertise `_audiobridge._udp` on the local network.
 *
 * NsdManager only allows one resolve at a time per client, so service resolution is
 * serialised on a single worker thread. Every resolved service is reported once.
 */
class NsdDiscovery(
    context: Context,
    private val onReceiver: (ReceiverInfo) -> Unit,
    private val onState: (searching: Boolean, message: String) -> Unit,
) {

    data class ReceiverInfo(val name: String, val host: String, val port: Int) {
        val key: String get() = "$host:$port"
    }

    private val resources: Resources = context.applicationContext.resources

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java)

    private val resolver = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nsd-resolve").apply { isDaemon = true }
    }
    private val seen = ConcurrentHashMap<String, Boolean>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        seen.clear()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started")
                onState(true, resources.getString(R.string.status_searching))
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running) return
                Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
                resolver.execute { resolve(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped")
                onState(false, resources.getString(R.string.status_search_stopped))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                running = false
                onState(false, resources.getString(R.string.status_discovery_failed, errorCode))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            running = false
            onState(false, resources.getString(R.string.status_discovery_error, e.message ?: ""))
        }
    }

    fun stop() {
        running = false
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery: ${e.message}")
            }
        }
        discoveryListener = null
        resolver.shutdownNow()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val latch = CountDownLatch(1)
        var resolved: ReceiverInfo? = null
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                    latch.countDown()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    val port = info.port
                    if (!host.isNullOrEmpty() && port in 1..65535) {
                        resolved = ReceiverInfo(info.serviceName ?: "AudioBridge", host, port)
                    }
                    latch.countDown()
                }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "resolve error: ${e.message}")
        }

        val receiver = resolved ?: return
        if (running && seen.putIfAbsent(receiver.key, true) == null) {
            Log.i(TAG, "Receiver: ${receiver.host}:${receiver.port}")
            onReceiver(receiver)
        }
    }

    private companion object {
        const val TAG = "AudioBridgeNsd"
    }
}
