package com.remotemouse

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets

class ClientActivity : AppCompatActivity() {
    private val TAG = "WiFiMouse-CLIENT"
    private val PORT = 8888
    
    private lateinit var etIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDebug: TextView
    private lateinit var touchpad: View
    private lateinit var btnClick: Button
    private lateinit var btnBack: Button
    
    private var socket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var isConnected = false
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastX = 0f
    private var lastY = 0f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        
        etIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnectClient)
        tvStatus = findViewById(R.id.tvClientStatus)
        tvDebug = findViewById(R.id.tvClientDebug)
        touchpad = findViewById(R.id.touchpadClient)
        btnClick = findViewById(R.id.btnClickClient)
        btnBack = findViewById(R.id.btnBackToMainClient)
        
        btnConnect.setOnClickListener { connect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnBack.setOnClickListener { finish() }
        btnClick.setOnClickListener { sendCommand("CLICK") }
        
        setupTouchpad()
        updateUI(false)
    }
    
    private fun debugLog(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread { tvDebug.append("$msg\n") }
    }
    
    private fun setupTouchpad() {
        touchpad.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        sendCommand("MOVE|$dx|$dy")
                        lastX = event.x; lastY = event.y
                    }
                }
            }
            true
        }
    }
    
    private fun connect() {
        val ip = etIp.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Entrez l'IP du serveur", Toast.LENGTH_SHORT).show()
            return
        }
        
        isRunning = true
        updateUI(true)
        tvStatus.text = "🔌 Connexion à $ip:$PORT..."
        tvDebug.text = ""
        
        scope.launch {
            try {
                socket = Socket()
                socket!!.tcpNoDelay = true
                socket!!.keepAlive = true
                socket!!.connect(java.net.InetSocketAddress(ip, PORT), 10000)
                
                output = PrintWriter(BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), StandardCharsets.UTF_8)), true)
                input = BufferedReader(InputStreamReader(socket!!.getInputStream(), StandardCharsets.UTF_8))
                
                isConnected = true
                debugLog("✅ Connecté à $ip:$PORT")
                
                runOnUiThread {
                    tvStatus.text = "✅ CONNECTÉ !\nDéplacez votre doigt sur le pavé."
                }
                
                while (isRunning && isConnected) {
                    try {
                        val line = input!!.readLine() ?: break
                        debugLog("📩 $line")
                    } catch (e: Exception) { break }
                }
            } catch (e: Exception) {
                debugLog("❌ ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ ÉCHEC: ${e.message}"
                    Toast.makeText(this, "Connexion échouée", Toast.LENGTH_SHORT).show()
                }
                isConnected = false
                updateUI(false)
            }
        }
    }
    
    private fun sendCommand(cmd: String) {
        if (!isConnected || output == null) {
            debugLog("❌ Non connecté")
            runOnUiThread { Toast.makeText(this, "Connectez-vous d'abord !", Toast.LENGTH_SHORT).show() }
            return
        }
        
        scope.launch {
            try {
                debugLog("📤 $cmd")
                output!!.println(cmd)
                output!!.flush()
                
                if (output!!.checkError()) {
                    debugLog("❌ ERREUR ENVOI")
                    isConnected = false
                    runOnUiThread {
                        tvStatus.text = "❌ Connexion perdue"
                        updateUI(false)
                    }
                }
            } catch (e: Exception) {
                debugLog("❌ ${e.message}")
                isConnected = false
                updateUI(false)
            }
        }
    }
    
    private fun disconnect() {
        isRunning = false; isConnected = false
        try { input?.close(); output?.close(); socket?.close() } catch (e: Exception) {}
        input = null; output = null; socket = null
        tvStatus.text = "🔌 Déconnecté"
        tvDebug.text = ""
        updateUI(false)
    }
    
    private fun updateUI(connecting: Boolean) {
        btnConnect.isEnabled = !isConnected && !connecting
        btnDisconnect.isEnabled = isConnected
        etIp.isEnabled = !isConnected
        touchpad.isEnabled = isConnected
        btnClick.isEnabled = isConnected
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
    }
}
