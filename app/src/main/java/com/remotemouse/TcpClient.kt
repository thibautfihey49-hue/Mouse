package com.remotemouse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.Socket

class TcpClient {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    var isConnected: Boolean = false
        private set

    suspend fun connect(host: String, port: Int = 8888): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = Socket(host, port)
            writer = PrintWriter(socket?.getOutputStream(), true)
            isConnected = true
            Log.d("RemoteMouse", "Connecté à $host:$port")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isConnected = false
            false
        }
    }

    fun sendCommand(command: String) {
        if (!isConnected) return
        try { writer?.println(command) }
        catch (e: Exception) { e.printStackTrace() }
    }

    fun disconnect() {
        isConnected = false
        try { writer?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        writer = null; socket = null
    }
}
