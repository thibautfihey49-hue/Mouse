package com.remotemouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class TcpServer(private val port: Int = 8888) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var job: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    
    var onCommandReceived: ((String) -> Unit)? = null
    var isRunning: Boolean = false
        private set
    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    fun start(context: Context, deviceName: String = "RemoteMouse-${android.os.Build.MODEL.take(6)}") {
        if (isRunning) return
        isRunning = true
        
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                val actualPort = serverSocket?.localPort ?: port
                Log.d("RemoteMouse", "✅ Serveur sur port $actualPort")
                
                nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = deviceName
                    serviceType = "_remotemouse._tcp."
                    this.port = actualPort
                }
                
                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(service: NsdServiceInfo) {
                        Log.d("RemoteMouse", "📢 Annoncé: ${service.serviceName}")
                    }
                    override fun onServiceUnregistered(service: NsdServiceInfo) {}
                    override fun onRegistrationFailed(service: NsdServiceInfo, e: Int) {
                        Log.e("RemoteMouse", "❌ Échec annonce: $e")
                    }
                    override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
                }
                
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                
                while (isRunning) {
                    try {
                        Log.d("RemoteMouse", "⏳ En attente...")
                        clientSocket = serverSocket?.accept() ?: break
                        Log.d("RemoteMouse", "🔗 Client connecté: ${clientSocket?.inetAddress}")
                        
                        withContext(Dispatchers.Main) { onClientConnected?.invoke() }
                        
                        val reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                        while (isRunning && clientSocket?.isConnected == true) {
                            val cmd = reader.readLine() ?: break
                            Log.d("RemoteMouse", "📥 Reçu: $cmd")
                            withContext(Dispatchers.Main) { onCommandReceived?.invoke(cmd) }
                        }
                    } catch (e: Exception) {
                        Log.e("RemoteMouse", "Erreur: ${e.message}")
                    } finally {
                        try { clientSocket?.close() } catch {}
                        clientSocket = null
                        if (isRunning) withContext(Dispatchers.Main) { onClientDisconnected?.invoke() }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteMouse", "❌ Serveur: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch {}
        try { clientSocket?.close() } catch {}
        try { serverSocket?.close() } catch {}
        clientSocket = null; serverSocket = null; nsdManager = null
    }
}
