package com.podmix.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BoseDevice(
    val name: String,
    val ip: String,
    val port: Int = 8090
)

@Singleton
class BoseDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _devices = MutableStateFlow<List<BoseDevice>>(emptyList())
    val devices: StateFlow<List<BoseDevice>> = _devices

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    fun startDiscovery() {
        if (isDiscovering) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        _devices.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("BoseDiscovery", "Discovery started")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("BoseDiscovery", "Found: ${serviceInfo.serviceName}")
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w("BoseDiscovery", "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val ip = si.host?.hostAddress ?: return
                        val device = BoseDevice(
                            name = si.serviceName,
                            ip = ip,
                            port = si.port
                        )
                        val current = _devices.value.toMutableList()
                        if (current.none { it.ip == ip }) {
                            current.add(device)
                            _devices.value = current
                        }
                        Log.d("BoseDiscovery", "Resolved: ${device.name} @ $ip")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _devices.value = _devices.value.filter { it.name != serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Log.e("BoseDiscovery", "Start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("BoseDiscovery", "Stop failed: $errorCode")
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices("_soundtouch._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e("BoseDiscovery", "discoverServices failed: ${e.message}")
            isDiscovering = false
        }
    }

    fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) { }
        }
        isDiscovering = false
    }
}
