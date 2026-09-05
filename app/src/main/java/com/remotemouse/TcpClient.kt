package com.remotemouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.Socket

class TcpClient(private val context: Context) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val foundServices = mutableListOf<NsdServiceInfo>()
    
    var isConnected: Boolean = false
        private set
    var onDeviceFound: ((List<String>) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onConnectionFailed: (() -> Unit)? = null

    fun startDiscovery() {
        foundServices.clear()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("RemoteMouse", "🔍 Recherche en cours...")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == "_remotemouse._tcp.") {
                    if (!foundServices.any { it.serviceName == service.serviceName }) {
                        foundServices.add(service)
                        val deviceNames = foundServices.map { it.serviceName }
                        Log.d("RemoteMouse", "✅ Appareil trouvé : ${service.serviceName}")
                        onDeviceFound?.invoke(deviceNames)
                        
                        if (foundServices.size == 1) {
                            connectToService(service)
                        }
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                foundServices.removeAll { it.serviceName == service.serviceName }
                onDeviceFound?.invoke(foundServices.map { it.serviceName })
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("RemoteMouse", "❌ Recherche impossible (code $errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager.discoverServices("_remotemouse._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (e: Exception) {}
        foundServices.clear()
    }

    private fun connectToService(service: NsdServiceInfo) {
        stopDiscovery()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(service.host, service.port)
                writer = PrintWriter(socket?.getOutputStream(), true)
                isConnected = true
                Log.d("RemoteMouse", "✅ Connecté à ${service.serviceName} !")
                withContext(Dispatchers.Main) {
                    onConnected?.invoke()
                }
            } catch (e: Exception) {
                Log.e("RemoteMouse", "❌ Connexion échouée : ${e.message}")
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionFailed?.invoke()
                }
            }
        }
    }

    fun sendCommand(command: String) {
        if (!isConnected) return
        try { writer?.println(command) }
        catch (e: Exception) { e.printStackTrace() }
    }

    fun disconnect() {
        isConnected = false
        stopDiscovery()
        try { writer?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        writer = null; socket = null
    }
}
