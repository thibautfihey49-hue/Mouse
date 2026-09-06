package com.remotemouse

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val TAG = "MouseApp"
    private val BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    private lateinit var tvStatus: TextView
    private lateinit var btnServer: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var touchpad: View
    private lateinit var btnClick: Button
    private lateinit var cursor: View
    
    private var btAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isServer = false
    private var isManuallyDisconnected = false
    private var job: Job? = null
    private var readingJob: Job? = null
    private var keepAliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val handler = Handler(Looper.getMainLooper())
    
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var cursorX = 0f
    private var cursorY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        tvStatus = findViewById(R.id.tvStatus)
        btnServer = findViewById(R.id.btnServer)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        touchpad = findViewById(R.id.touchpad)
        btnClick = findViewById(R.id.btnClick)
        cursor = findViewById(R.id.cursor)
        
        btnServer.setOnClickListener { startServer() }
        btnConnect.setOnClickListener { showDevices() }
        btnDisconnect.setOnClickListener { manualDisconnect() }
        btnClick.setOnClickListener { sendCommand("CLICK\n") }
        
        setupTouchpad()
        initBluetooth()
    }

    private fun setupTouchpad() {
        touchpad.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                        sendCommand("MOVE|$dx|$dy\n")
                        cursorX += dx * 0.5f
                        cursorY += dy * 0.5f
                        cursorX = cursorX.coerceIn(10f, 600f)
                        cursorY = cursorY.coerceIn(10f, 400f)
                        cursor.x = cursorX
                        cursor.y = cursorY
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            true
        }
    }

    private fun initBluetooth() {
        val manager = getSystemService(BluetoothManager::class.java)
        btAdapter = manager.adapter
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth non disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        checkPermissions()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (perms.isNotEmpty()) {
                requestPermissions(perms.toTypedArray(), 100)
                return
            }
        }
        checkBluetoothOn()
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (g.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) checkBluetoothOn()
        else { Toast.makeText(this, "Permissions requises", Toast.LENGTH_SHORT).show(); finish() }
    }

    private fun checkBluetoothOn() {
        if (btAdapter?.isEnabled == true) ready()
        else startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 101)
    }

    private fun ready() {
        updateUI(false)
        tvStatus.text = "✅ Prêt\n\n📱 Serveur: Démarrer + activer accessibilité\n📲 Client: Se connecter"
    }

    private fun startServer() {
        if (!isAccessibilityOn()) {
            Toast.makeText(this, "👉 Active l'accessibilité dans les paramètres", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        
        isServer = true
        isManuallyDisconnected = false
        updateUI(true, true)
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        
        tvStatus.text = "⏳ Serveur en écoute...\nNom: ${btAdapter?.name}"
        
        job = scope.launch {
            while (isActive && !isManuallyDisconnected) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        return@launch
                    
                    val serverSocket = btAdapter?.listenUsingRfcommWithServiceRecord("Mouse", BT_UUID)
                    Log.d(TAG, "Serveur: en attente de connexion...")
                    
                    val clientSocket = serverSocket?.accept()
                    serverSocket?.close()
                    
                    clientSocket?.let {
                        socket = it
                        val device = it.remoteDevice
                        outputStream = it.outputStream
                        inputStream = it.inputStream
                        
                        Log.d(TAG, "✅ Connecté à: ${device.name}")
                        
                        runOnUiThread {
                            tvStatus.text = "✅ CONNECTÉ À ${device.name}\n\n🖱️ Déplacez votre doigt !"
                            updateUI(true, false)
                        }
                        
                        startKeepAlive()
                        startReading()
                        
                        // Attendre déconnexion
                        while (socket?.isConnected == true && !isManuallyDisconnected) {
                            delay(500)
                        }
                        
                        if (!isManuallyDisconnected) {
                            Log.d(TAG, "🔌 Connexion perdue — Reconnexion dans 2s...")
                            cleanupConnection()
                            runOnUiThread {
                                tvStatus.text = "🔌 Connexion perdue\n⏳ Reconnexion automatique..."
                            }
                            delay(2000)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Serveur erreur: ${e.message}")
                    if (!isManuallyDisconnected) {
                        delay(1500)
                    }
                }
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && socket?.isConnected == true) {
                try {
                    outputStream?.write("PING\n".toByteArray())
                    outputStream?.flush()
                    delay(15000) // Envoyer un PING toutes les 15s
                } catch (e: Exception) {
                    Log.d(TAG, "KeepAlive échoué: ${e.message}")
                    break
                }
            }
        }
    }

    private fun startReading() {
        readingJob?.cancel()
        readingJob = scope.launch {
            try {
                val buffer = ByteArray(1024)
                var accumulated = ""
                
                while (isActive && socket?.isConnected == true && !isManuallyDisconnected) {
                    val bytes = inputStream?.read(buffer) ?: -1
                    
                    if (bytes == -1) {
                        Log.w(TAG, "Socket fermée par l'autre appareil")
                        break
                    }
                    
                    if (bytes > 0) {
                        val chunk = String(buffer, 0, bytes)
                        accumulated += chunk
                        
                        while (accumulated.contains("\n")) {
                            val lineEnd = accumulated.indexOf("\n")
                            val line = accumulated.substring(0, lineEnd).trim()
                            accumulated = accumulated.substring(lineEnd + 1)
                            
                            when {
                                line.isEmpty() -> {}
                                line == "PING" -> Log.d(TAG, "PING reçu")
                                else -> {
                                    Log.d(TAG, "→ Commande: '$line'")
                                    InputDispatcher.handleCommand(line)
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "Boucle de lecture terminée")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lecture: ${e.message}")
            } finally {
                if (!isManuallyDisconnected) {
                    runOnUiThread {
                        tvStatus.text = "🔌 Connexion perdue\n⏳ Reconnexion..."
                    }
                    cleanupConnection()
                }
            }
        }
    }

    private fun showDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return
        
        val devices = btAdapter?.bondedDevices ?: emptySet()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Associe d'abord les 2 appareils dans les paramètres Bluetooth", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        
        val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        val list = devices.toList()
        AlertDialog.Builder(this)
            .setTitle("Choisis un appareil")
            .setItems(names) { _, i -> connectTo(list[i]) }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        isServer = false
        isManuallyDisconnected = false
        updateUI(true, true)
        tvStatus.text = "🔌 Connexion à ${device.name}..."
        
        scope.launch {
            var retryCount = 0
            val maxRetries = 5
            
            while (retryCount < maxRetries && !isManuallyDisconnected) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        return@launch
                    
                    socket = device.createRfcommSocketToServiceRecord(BT_UUID)
                    socket?.connect()
                    
                    outputStream = socket?.outputStream
                    inputStream = socket?.inputStream
                    
                    Log.d(TAG, "✅ Client connecté ! Tentative ${retryCount+1}")
                    
                    runOnUiThread {
                        tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Déplacez votre doigt sur le pavé"
                        updateUI(true, false)
                    }
                    
                    startKeepAlive()
                    startReading()
                    
                    // Surveiller la connexion
                    while (socket?.isConnected == true && !isManuallyDisconnected) {
                        delay(500)
                    }
                    
                    if (!isManuallyDisconnected) {
                        Log.d(TAG, "🔌 Connexion perdue — Reconnexion...")
                        cleanupConnection()
                        retryCount++
                        runOnUiThread {
                            tvStatus.text = "🔌 Connexion perdue\n⏳ Reconnexion ${retryCount}/${maxRetries}..."
                        }
                        delay(2000)
                    }
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Connexion échouée (${retryCount+1}/${maxRetries}): ${e.message}")
                    retryCount++
                    cleanupConnection()
                    runOnUiThread {
                        tvStatus.text = "❌ ÉCHEC ${retryCount}/${maxRetries}:\n${e.message}\n\nNouvelle tentative..."
                    }
                    delay(1500)
                }
            }
            
            if (retryCount >= maxRetries) {
                runOnUiThread {
                    tvStatus.text = "❌ Impossible de se connecter\n\nVérifie que le serveur est démarré"
                    resetUI()
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        if (outputStream == null || socket?.isConnected != true) {
            Toast.makeText(this, "Pas connecté — attendez reconnexion...", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            outputStream?.write(cmd.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
            Log.d(TAG, "Envoyé: ${cmd.trim()}")
        } catch (e: Exception) {
            Log.e(TAG, "Envoi échoué: ${e.message}")
        }
    }

    private fun cleanupConnection() {
        keepAliveJob?.cancel()
        readingJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }

    private fun manualDisconnect() {
        isManuallyDisconnected = true
        job?.cancel()
        cleanupConnection()
        tvStatus.text = "🔌 Déconnecté"
        resetUI()
    }

    private fun resetUI() { updateUI(false, false); isServer = false; }
    private fun updateUI(connected: Boolean, connecting: Boolean = false) {
        btnServer.isEnabled = !connected && !connecting
        btnConnect.isEnabled = !connected && !connecting
        btnDisconnect.isEnabled = connected
        touchpad.isEnabled = connected
        btnClick.isEnabled = connected
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    override fun onActivityResult(r: Int, code: Int, d: Intent?) {
        super.onActivityResult(r, code, d)
        if (r == 101) if (code == RESULT_OK) ready() else finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isManuallyDisconnected = true
        job?.cancel()
        keepAliveJob?.cancel()
        readingJob?.cancel()
        cleanupConnection()
    }
}
