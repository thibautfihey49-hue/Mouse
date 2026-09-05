package com.remotemouse

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
    var onCommandReceived: ((String) -> Unit)? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d("RemoteMouse", "Serveur démarré sur port $port")
                while (isRunning) {
                    try {
                        clientSocket = serverSocket?.accept() ?: break
                        val reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                        while (isRunning && clientSocket?.isConnected == true) {
                            val command = reader.readLine() ?: break
                            command?.let {
                                Log.d("RemoteMouse", "Reçu: $it")
                                withContext(Dispatchers.Main) {
                                    onCommandReceived?.invoke(it)
                                }
                            }
                        }
                    } catch (e: Exception) { if (isRunning) e.printStackTrace() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        try { clientSocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
