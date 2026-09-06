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

        checkAccessibilityPermission()
        setupListeners()
    }

    private fun setupListeners() {
        btnStartServer.setOnClickListener {
            if (!isServerMode) startServer() else stopServer()
        }

        btnConnect.setOnClickListener {
            if (!client.isConnected) {
                startClientDiscovery()
            }
        }

        btnDisconnect.setOnClickListener {
            client.disconnect()
            updateUI()
            tvStatus.text = "🔌 Déconnecté"
        }

        touchpad.onTouchListener = { dx, dy ->
            if (client.isConnected) client.sendCommand("MOVE|$dx|$dy")
        }

        btnLeftClick.setOnClickListener {
            if (client.isConnected) client.sendCommand("CLICK")
        }
        btnRightClick.setOnClickListener {
            if (client.isConnected) client.sendCommand("RIGHT_CLICK")
        }

        client.onDeviceFound = { devices ->
            if (devices.isEmpty()) {
                tvStatus.text = "🔍 Aucun appareil trouvé\nMême Wi-Fi ?"
            } else if (devices.size == 1) {
                tvStatus.text = "✅ ${devices[0]} détecté... Connexion automatique !"
            } else {
                tvStatus.text = "✅ ${devices.size} appareils détectés"
            }
        }

        client.onConnected = {
            tvStatus.text = "✅ CONNECTÉ ! Utilisez le pavé"
            updateUI()
        }

        client.onConnectionFailed = {
            tvStatus.text = "❌ Échec de la connexion\nRéessayez..."
            btnConnect.isEnabled = true
        }

        server.onCommandReceived = { command ->
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    InputDispatcher.dispatchCommand(command)
                }
                tvStatus.text = "✅ Prêt — Commande reçue"
            }
        }
    }

    private fun startServer() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "⚠️ Activez l'accessibilité pour RemoteMouse", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        isServerMode = true
        server.start(this)
        btnStartServer.text = "🛑 Arrêter le serveur"
        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = false
        tvStatus.text = "✅ SERVEUR ACTIF\nEn attente de connexion..."
    }

    private fun stopServer() {
        isServerMode = false
        server.stop()
        btnStartServer.text = "▶️ Démarrer le serveur"
        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        tvStatus.text = "Serveur arrêté"
    }

    private fun startClientDiscovery() {
        tvStatus.text = "🔍 Recherche d'appareils..."
        btnConnect.isEnabled = false
        client.startDiscovery()
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!client.isConnected) {
                client.stopDiscovery()
                tvStatus.text = "⚠️ Aucun appareil trouvé\n• Même Wi-Fi ?\n• Serveur démarré ?"
                btnConnect.isEnabled = true
            }
        }, 15000)
    }

    private fun updateUI() {
        val connected = client.isConnected
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        btnStartServer.isEnabled = !connected
    }

    private fun checkAccessibilityPermission() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "👉 Activez l'accessibilité pour RemoteMouse", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        client.disconnect()
    }
}

class TouchpadView(context: android.content.Context, attrs: android.util.AttributeSet) :
    android.view.View(context, attrs) {
    var onTouchListener: ((dx: Float, dy: Float) -> Unit)? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) > 2 || abs(dy) > 2) {
                    onTouchListener?.invoke(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }
        }
        return true
    }
}
