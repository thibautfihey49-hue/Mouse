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
    private lateinit var tvLog: TextView
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
        tvLog = findViewById(R.id.tvServerLog)
        btnStart = findViewById(R.id.btnStartServer)
        btnBack = findViewById(R.id.btnBackServer)
        
        btnStart.setOnClickListener { toggleServer() }
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
            runOnUiThread {
                tvStatus.text = "⚠️ Accessibilité requise"
                btnStart.text = "🔧 ACTIVER L'ACCESSIBILITÉ"
                btnStart.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this@ServerActivity, 
                        "Recherchez WiFiMouse → Activez → Revenez ici", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun toggleServer() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val myService = "$packageName/.AccessibilityInputService"
        if (enabled == null || !enabled.contains(myService)) {
            Toast.makeText(this@ServerActivity, "Activez d'abord l'accessibilité", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!isRunning) startServer() else stopServer()
    }
    
    private fun startServer() {
        isRunning = true
        runOnUiThread {
            btnStart.text = "⏹️ ARRÊTER LE SERVEUR"
            tvStatus.text = "🟡 Démarrage..."
            tvLog.text = "📋 Journal:\n"
        }
        
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                runOnUiThread {
                    tvStatus.text = "✅ En attente de connexion sur le port $PORT"
                    log("Serveur démarré sur $PORT")
                }
                
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        Log.d(TAG, "Client connecté: ${client.inetAddress}")
                        runOnUiThread {
                            tvStatus.text = "✅ CONNECTÉ à ${client.inetAddress}"
                            log("Client connecté: ${client.inetAddress}")
                        }
                        
                        clientSocket = client
                        input = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                        output = PrintWriter(BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)), true)
                        
                        while (isRunning && !client.isClosed) {
                            try {
                                val line = input!!.readLine() ?: break
                                if (line.isNotEmpty()) {
                                    Log.d(TAG, "REÇU: $line")
                                    runOnUiThread { log("← $line") }
                                    InputDispatcher.handleCommand(line)
                                    output?.println("OK")
                                    output?.flush()
                                }
                            } catch (e: Exception) {
                                log("Erreur lecture: ${e.message}")
                                break
                            }
                        }
                        
                        cleanup()
                        runOnUiThread {
                            tvStatus.text = "🟡 Déconnecté — En attente..."
                            log("Client déconnecté")
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur serveur: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ Erreur: ${e.message}"
                    log("ERREUR: ${e.message}")
                }
            }
        }
    }
    
    private fun stopServer() {
        isRunning = false
        cleanup()
        try { serverSocket?.close() } catch (e: Exception) {}
        runOnUiThread {
            btnStart.text = "▶️ DÉMARRER LE SERVEUR"
            tvStatus.text = "⏹️ Serveur arrêté"
            log("Serveur arrêté")
        }
    }
    
    private fun cleanup() {
        try { input?.close(); output?.close(); clientSocket?.close() } catch (e: Exception) {}
        input = null; output = null; clientSocket = null
    }
    
    private fun log(msg: String) {
        runOnUiThread { tvLog.append("$msg\n") }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        cleanup()
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
