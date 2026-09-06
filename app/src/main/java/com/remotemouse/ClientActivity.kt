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
    private lateinit var tvLog: TextView
    private lateinit var touchpad: View
    private lateinit var btnLeftClick: Button
    private lateinit var btnBack: Button
    
    private var socket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var isConnected = false
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastX = 0f
    private var lastY = 0f
    private var moveThreshold = 4f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        
        etIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvClientStatus)
        tvLog = findViewById(R.id.tvClientLog)
        touchpad = findViewById(R.id.touchpad)
        btnLeftClick = findViewById(R.id.btnLeftClick)
        btnBack = findViewById(R.id.btnBackClient)
        
        btnConnect.setOnClickListener { connect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnBack.setOnClickListener { finish() }
        btnLeftClick.setOnClickListener { sendCommand("CLICK") }
        
        setupTouchpad()
        updateUI()
    }
    
    private fun log(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread { tvLog.append("$msg\n") }
    }
    
    private fun setupTouchpad() {
        touchpad.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (Math.abs(dx) > moveThreshold || Math.abs(dy) > moveThreshold) {
                        sendCommand("MOVE|$dx|$dy")
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
            true
        }
    }
    
    private fun connect() {
        val ip = etIp.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this@ClientActivity, "Entrez l'IP du serveur", Toast.LENGTH_SHORT).show()
            return
        }
        
        isRunning = true
        runOnUiThread {
            tvStatus.text = "🔌 Connexion..."
            tvLog.text = "📋 Journal:\n"
        }
        
        scope.launch {
            try {
                socket = Socket()
                socket!!.tcpNoDelay = true
                socket!!.keepAlive = true
                socket!!.connect(java.net.InetSocketAddress(ip, PORT), 8000)
                
                output = PrintWriter(BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), StandardCharsets.UTF_8)), true)
                input = BufferedReader(InputStreamReader(socket!!.getInputStream(), StandardCharsets.UTF_8))
                
                isConnected = true
                log("✅ Connecté à $ip:$PORT")
                runOnUiThread {
                    tvStatus.text = "✅ CONNECTÉ !\nDéplacez votre doigt sur le pavé"
                    updateUI()
                }
                
                while (isRunning && isConnected) {
                    try {
                        val line = input!!.readLine() ?: break
                        log("← $line")
                    } catch (e: Exception) { break }
                }
            } catch (e: Exception) {
                log("❌ ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ Échec: ${e.message}"
                    Toast.makeText(this@ClientActivity, "Impossible de se connecter", Toast.LENGTH_SHORT).show()
                    isConnected = false
                    updateUI()
                }
            }
        }
    }
    
    private fun sendCommand(cmd: String) {
        if (!isConnected || output == null) {
            runOnUiThread {
                Toast.makeText(this@ClientActivity, "Connectez-vous d'abord", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        scope.launch {
            try {
                log("→ $cmd")
                output!!.println(cmd)
                output!!.flush()
                if (output!!.checkError()) throw IOException("Erreur d'envoi")
            } catch (e: Exception) {
                log("❌ ${e.message}")
                isConnected = false
                runOnUiThread {
                    tvStatus.text = "❌ Connexion perdue"
                    updateUI()
                }
            }
        }
    }
    
    private fun disconnect() {
        isRunning = false
        isConnected = false
        try { input?.close(); output?.close(); socket?.close() } catch (e: Exception) {}
        input = null; output = null; socket = null
        runOnUiThread {
            tvStatus.text = "🔌 Déconnecté"
            updateUI()
        }
    }
    
    private fun updateUI() {
        btnConnect.isEnabled = !isConnected
        btnDisconnect.isEnabled = isConnected
        etIp.isEnabled = !isConnected
        touchpad.isEnabled = isConnected
        btnLeftClick.isEnabled = isConnected
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
    }
}
