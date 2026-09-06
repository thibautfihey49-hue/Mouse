package com.remotemouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class TcpClient(private val context: Context) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdManager: NsdManager? = null
    private val foundServices = mutableListOf<NsdServiceInfo>()
    private var connectJob: Job? = null
    
    var isConnected: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set
    var onDeviceFound: ((List<String>) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onConnectionFailed: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private fun runOnUiThread(action: () -> Unit) {
        (context as? android.app.Activity)?.runOnUiThread { action() }
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        foundServices.clear()
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("RemoteMouse", "🔍 Recherche démarrée")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("RemoteMouse", "✅ Trouvé: ${service.serviceName}")
                if (service.serviceType != "_remotemouse._tcp.") return
                if (foundServices.any { it.serviceName == service.serviceName }) return
                foundServices.add(service)
                runOnUiThread { onDeviceFound?.invoke(foundServices.map { it.serviceName }) }
                if (foundServices.size == 1) resolveAndConnect(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                foundServices.removeAll { it.serviceName == service.serviceName }
                runOnUiThread { onDeviceFound?.invoke(foundServices.map { it.serviceName }) }
            }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {
                runOnUiThread { onConnectionFailed?.invoke("Recherche impossible") }
            }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        try {
            nsdManager?.discoverServices("_remotemouse._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("RemoteMouse", "❌ Erreur découverte: ${e.message}")
        }
    }

    private fun resolveAndConnect(service: NsdServiceInfo) {
        if (isConnected || isConnecting) return
        isConnecting = true
        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, e: Int) {
                isConnecting = false
                runOnUiThread { onConnectionFailed?.invoke("Impossible de résoudre l'adresse") }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                Log.d("RemoteMouse", "✅ Résolu: ${info.host}:${info.port}")
                connectTo(info.host, info.port)
            }
        })
    }

    private fun connectTo(host: java.net.InetAddress, port: Int) {
        connectJob?.cancel()
        connectJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("RemoteMouse", "🔌 Connexion à $host:$port...")
                socket = Socket().apply {
                    soTimeout = 5000
                    connect(InetSocketAddress(host, port), 5000)
                }
                writer = PrintWriter(socket?.getOutputStream(), true)
                isConnected = true
                isConnecting = false
                Log.d("RemoteMouse", "✅ CONNECTÉ !")
                runOnUiThread { onConnected?.invoke() }
            } catch (e: Exception) {
                Log.e("RemoteMouse", "❌ Échec: ${e.message}")
                isConnected = false
                isConnecting = false
                runOnUiThread {
                    val msg = when {
                        e.message?.contains("timed out") == true -> "Délai dépassé — Serveur démarré ?"
                        e.message?.contains("refused") == true -> "Connexion refusée — Même Wi-Fi ?"
                        else -> "Erreur: ${e.message}"
                    }
                    onConnectionFailed?.invoke(msg)
                }
            }
        }
    }

    fun stopDiscovery() {
        try { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (e: Exception) {}
        foundServices.clear()
    }

    fun sendCommand(cmd: String): Boolean {
        if (!isConnected || writer == null) return false
        return try {
            writer?.println(cmd)
            true
        } catch (e: Exception) {
            isConnected = false
            runOnUiThread { onDisconnected?.invoke() }
            false
        }
    }

    fun disconnect() {
        isConnecting = false
        isConnected = false
        connectJob?.cancel()
        try { writer?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        writer = null
        socket = null
        runOnUiThread { onDisconnected?.invoke() }
    }
}
