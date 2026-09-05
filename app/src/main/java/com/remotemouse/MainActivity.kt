package com.remotemouse

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnStartServer: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var btnLeftClick: Button
    private lateinit var btnRightClick: Button
    private lateinit var etKeyboard: EditText
    private lateinit var btnSendText: Button

    private val server = TcpServer()
    private val client = TcpClient()
    private var isServerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etIp = findViewById(R.id.etIp)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartServer = findViewById(R.id.btnStartServer)
        touchpad = findViewById(R.id.touchpad)
        btnLeftClick = findViewById(R.id.btnLeftClick)
        btnRightClick = findViewById(R.id.btnRightClick)
        etKeyboard = findViewById(R.id.etKeyboard)
        btnSendText = findViewById(R.id.btnSendText)

        checkAccessibilityPermission()

        btnStartServer.setOnClickListener {
            if (!isServerMode) startServer() else stopServer()
        }

        btnConnect.setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isNotEmpty()) connectToDevice(ip)
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

        btnSendText.setOnClickListener {
            val text = etKeyboard.text.toString()
            if (text.isNotEmpty() && client.isConnected) {
                client.sendCommand("TEXT|$text")
                etKeyboard.text.clear()
            }
        }

        server.onCommandReceived = { command ->
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    InputDispatcher.dispatchCommand(command)
                }
                tvStatus.text = when {
                    command.startsWith("MOVE") -> "Déplacement"
                    command == "CLICK" -> "Clic gauche"
                    command == "RIGHT_CLICK" -> "Clic droit"
                    else -> "Reçu: $command"
                }
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
        server.start()
        btnStartServer.text = "🛑 Arrêter le serveur"
        btnConnect.isEnabled = false
        etIp.isEnabled = false
        val ip = getLocalIpAddress()
        tvStatus.text = "✅ Serveur en ligne\nIP: $ip:8888"
        Toast.makeText(this, "Serveur démarré !", Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        isServerMode = false
        server.stop()
        btnStartServer.text = "▶️ Démarrer le serveur"
        btnConnect.isEnabled = true
        etIp.isEnabled = true
        tvStatus.text = "Serveur arrêté"
    }

    private fun connectToDevice(ip: String) {
        CoroutineScope(Dispatchers.Main).launch {
            tvStatus.text = "Connexion à $ip..."
            val success = client.connect(ip)
            if (success) {
                tvStatus.text = "✅ Connecté à $ip\nUtilisez le pavé ci-dessous"
                btnConnect.text = "🔴 Déconnecter"
                btnConnect.setOnClickListener {
                    client.disconnect()
                    tvStatus.text = "Déconnecté"
                    btnConnect.text = "🔌 Se connecter"
                    btnConnect.setOnClickListener { connectToDevice(etIp.text.toString().trim()) }
                }
            } else {
                tvStatus.text = "❌ Échec de la connexion"
            }
        }
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

    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format("%d.%d.%d.%d",
            ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
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
