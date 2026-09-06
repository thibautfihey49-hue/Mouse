package com.remotemouse

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets

class ServerActivity : AppCompatActivity() {
    private val TAG = "WiFiMouse-SERVER"
    private val PORT = 8888
    
    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvReceived: TextView
    private lateinit var btnStart: Button
    private lateinit var btnBack: Button
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var input: BufferedReader? = null
    private var output: PrintWriter? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        
        tvStatus = findViewById(R.id.tvServerStatus)
        tvIp = findViewById(R.id.tvServerIp)
        tvReceived = findViewById(R.id.tvReceivedCommands)
        btnStart = findViewById(R.id.btnStartServer)
        btnBack = findViewById(R.id.btnBackToMain)
        
        btnStart.setOnClickListener { startServer() }
        btnBack.setOnClickListener { finish() }
        
        tvIp.text = "🌐 IP: ${getLocalIP()}"
        checkAccessibility()
    }
    
    private fun getLocalIP(): String {
        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiMgr.connectionInfo.ipAddress
        return String.format("%d.%d.%d.%d", ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
    }
    
    private fun checkAccessibility() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val myService = "$packageName/.AccessibilityInputService"
        if (enabled == null || !enabled.contains(myService)) {
            tvStatus.text = "⚠️ ACTIVEZ L'ACCESSIBILITÉ !"
            btnStart.text = "🔧 ACTIVER L'ACCESSIBILITÉ"
            btnStart.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@ServerActivity, "Recherchez WiFiMouse → Activez", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun startServer() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val myService = "$packageName/.AccessibilityInputService"
        if (enabled == null || !enabled.contains(myService)) {
            Toast.makeText(this@ServerActivity, "Activez l'accessibilité d'abord !", Toast.LENGTH_LONG).show()
            return
        }
        
        isRunning = true
        btnStart.isEnabled = false
        tvStatus.text = "🟡 DÉMARRAGE..."
        tvReceived.text = "📋 Commandes reçues:\n"
        
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                runOnUiThread {
                    tvStatus.text = "🟡 EN ÉCOUTE — Port $PORT\nEn attente de connexion..."
                }
                
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        Log.d(TAG, "✅ Client connecté: ${client.inetAddress}")
                        
                        runOnUiThread {
                            tvStatus.text = "✅ CONNECTÉ !\nDéplacez votre doigt sur le client."
                        }
                        
                        clientSocket = client
                        input = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                        output = PrintWriter(BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)), true)
                        
                        while (isRunning && !client.isClosed) {
                            try {
                                val line = input!!.readLine() ?: break
                                if (line.isNotEmpty()) {
                                    Log.d(TAG, "📥 COMMANDE: $line")
                                    runOnUiThread {
                                        tvReceived.append("$line\n")
                                    }
                                    InputDispatcher.handleCommand(line)
                                    output?.println("OK:$line")
                                    output?.flush()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Lecture: ${e.message}")
                                break
                            }
                        }
                        
                        cleanup()
                        runOnUiThread {
                            tvStatus.text = "🟡 Déconnecté\nEn attente..."
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Serveur: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ ERREUR: ${e.message}"
                }
            }
        }
    }
    
    private fun cleanup() {
        try { input?.close(); output?.close(); clientSocket?.close() } catch (e: Exception) {}
        input = null; output = null; clientSocket = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false; scope.cancel()
        cleanup()
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
