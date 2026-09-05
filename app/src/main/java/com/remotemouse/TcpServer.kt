package com.remotemouse

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
    var onCommandReceived: ((String) -> Unit)? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                // ✅ ÉCOUTE SUR TOUTES LES INTERFACES = 0.0.0.0
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("0.0.0.0", port))
                
                Log.d("RemoteMouse", "✅ Serveur démarré sur 0.0.0.0:$port")
                
                while (isRunning) {
                    try {
                        clientSocket = serverSocket?.accept() ?: break
                        Log.d("RemoteMouse", "🔗 Client connecté depuis ${clientSocket?.inetAddress}")
                        
                        val reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                        while (isRunning && clientSocket?.isConnected == true) {
                            val command = reader.readLine() ?: break
                            command?.let {
                                Log.d("RemoteMouse", "📥 Reçu: $it")
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
                Log.e("RemoteMouse", "Serveur erreur: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        try { clientSocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
