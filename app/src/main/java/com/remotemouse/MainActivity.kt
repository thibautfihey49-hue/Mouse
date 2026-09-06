package com.remotemouse

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnStartServer: Button
    private lateinit var btnConnect: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var btnLeftClick: Button
    private lateinit var btnRightClick: Button
    private lateinit var btnDisconnect: Button
    
    private val server = TcpServer()
    private lateinit var client: TcpClient
    private var isServerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartServer = findViewById(R.id.btnStartServer)
        btnConnect = findViewById(R.id.btnConnect)
        touchpad = findViewById(R.id.touchpad)
        btnLeftClick = findViewById(R.id.btnLeftClick)
        btnRightClick = findViewById(R.id.btnRightClick)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        client = TcpClient(this)
        setupListeners()
    }

    private fun setupListeners() {
        btnStartServer.setOnClickListener { if (!isServerMode) startServer() else stopServer() }
        btnConnect.setOnClickListener { if (!client.isConnected && !client.isConnecting) startDiscovery() }
        btnDisconnect.setOnClickListener { client.disconnect(); updateUI(); tvStatus.text = "🔌 Déconnecté" }
        
        touchpad.onTouchListener = { dx, dy -> if (client.isConnected) client.sendCommand("MOVE|$dx|$dy") }
        btnLeftClick.setOnClickListener { if (client.isConnected) client.sendCommand("CLICK") }
        btnRightClick.setOnClickListener { if (client.isConnected) client.sendCommand("RIGHT_CLICK") }

        client.onDeviceFound = { devices ->
            tvStatus.text = if (devices.isEmpty()) "🔍 Recherche en cours..." else "✅ ${devices.first()} détecté... Connexion..."
        }
        client.onConnected = { tvStatus.text = "✅ CONNECTÉ ! Utilisez le pavé"; updateUI() }
        client.onConnectionFailed = { err -> tvStatus.text = "❌ $err\n• Même Wi-Fi ?\n• Serveur démarré ?"; btnConnect.isEnabled = true }
        client.onDisconnected = { tvStatus.text = "🔌 Déconnecté"; updateUI() }

        server.onClientConnected = { tvStatus.text = "✅ Client connecté ! Contrôlez depuis l'autre téléphone" }
        server.onClientDisconnected = { tvStatus.text = "⏹️ En attente d'un client..." }
        server.onCommandReceived = { cmd ->
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) InputDispatcher.dispatchCommand(cmd)
            }
        }
    }

    private fun startServer() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "👉 Activez l'accessibilité pour RemoteMouse", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        isServerMode = true
        server.start(this)
        btnStartServer.text = "🛑 ARRÊTER LE SERVEUR"
        btnConnect.isEnabled = false
        tvStatus.text = "✅ SERVEUR EN ÉCOUTE\nEn attente de connexion..."
    }

    private fun stopServer() {
        isServerMode = false
        server.stop()
        btnStartServer.text = "▶️ DÉMARRER LE SERVEUR"
        btnConnect.isEnabled = true
        tvStatus.text = "Serveur arrêté"
    }

    private fun startDiscovery() {
        tvStatus.text = "🔍 Recherche d'appareils..."
        btnConnect.isEnabled = false
        client.startDiscovery()
        android.os.Handler(mainLooper).postDelayed({
            if (!client.isConnected) {
                client.stopDiscovery()
                btnConnect.isEnabled = true
                if (tvStatus.text.startsWith("🔍 Recherche")) {
                    tvStatus.text = "⏱️ Aucun appareil trouvé\n• Même Wi-Fi ?\n• Serveur démarré ?"
                }
            }
        }, 15000)
    }

    private fun updateUI() {
        val connected = client.isConnected
        btnConnect.isEnabled = !connected && !isServerMode
        btnDisconnect.isEnabled = connected
        btnStartServer.isEnabled = !connected
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    override fun onDestroy() { super.onDestroy(); server.stop(); client.disconnect() }
}

class TouchpadView(context: android.content.Context, attrs: android.util.AttributeSet) : android.view.View(context, attrs) {
    var onTouchListener: ((dx: Float, dy: Float) -> Unit)? = null
    private var lastX = 0f; private var lastY = 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (abs(dx) > 2 || abs(dy) > 2) { onTouchListener?.invoke(dx, dy); lastX = e.x; lastY = e.y }
            }
        }
        return true
    }
}
