package com.remotemouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
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

    fun start(context: Context, deviceName: String = "RemoteMouse-${android.os.Build.MODEL.take(8)}") {
        if (isRunning) return
        isRunning = true
        
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("0.0.0.0", port))
                val localPort = serverSocket?.localPort ?: port
                
                Log.d("RemoteMouse", "✅ Serveur démarré sur 0.0.0.0:$localPort")
                
                nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = deviceName
                    serviceType = "_remotemouse._tcp."
                    this.port = localPort
                }
                
                // ✅ NOMS DE MÉTHODES EXACTS DE L'INTERFACE ANDROID
                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(service: NsdServiceInfo) {
                        Log.d("RemoteMouse", "📢 Appareil visible : ${service.serviceName}")
                    }
                    override fun onServiceUnregistered(service: NsdServiceInfo) {}
                    override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                        Log.e("RemoteMouse", "⚠️ Annonce réseau impossible (code $errorCode)")
                    }
                    override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {}
                }
                
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                
                while (isRunning) {
                    try {
                        clientSocket = serverSocket?.accept() ?: break
                        Log.d("RemoteMouse", "🔗 Client connecté !")
                        
                        val reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                        while (isRunning && clientSocket?.isConnected == true) {
                            val command = reader.readLine() ?: break
                            command?.let {
                                withContext(Dispatchers.Main) {
                                    onCommandReceived?.invoke(it)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.e("RemoteMouse", "Erreur: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteMouse", "Serveur: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (e: Exception) {}
        try { clientSocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
