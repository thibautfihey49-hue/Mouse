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
    private var writer: PrintWriter? = null
    private var isServer = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
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
        btnDisconnect.setOnClickListener { disconnect() }
        btnClick.setOnClickListener { send("CLICK") }
        
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
                        send("MOVE|$dx|$dy")
                        // Curseur visible LOCALEMENT
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
        updateUI(true, true)
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        
        tvStatus.text = "⏳ Serveur en écoute...\nNom: ${btAdapter?.name}"
        
        job = scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    return@launch
                
                val server = btAdapter?.listenUsingRfcommWithServiceRecord("Mouse", BT_UUID)
                val clientSocket = server?.accept()
                server?.close()
                
                clientSocket?.let {
                    socket = it
                    val device = it.remoteDevice
                    Log.d(TAG, "Connecté à: ${device.name}")
                    
                    runOnUiThread {
                        tvStatus.text = "✅ CONNECTÉ À ${device.name}\n\n🖱️ Utilise le pavé tactile !"
                        updateUI(true, false)
                    }
                    
                    // Lire les commandes
                    val reader = BufferedReader(InputStreamReader(it.inputStream))
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, "Commande reçue: $line")
                        InputDispatcher.handleCommand(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Serveur erreur: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ Erreur: ${e.message}"
                    resetUI()
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
        
        val names = devices.map { it.name }.toTypedArray()
        val list = devices.toList()
        AlertDialog.Builder(this)
            .setTitle("Choisis un appareil")
            .setItems(names) { _, i -> connectTo(list[i]) }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        isServer = false
        updateUI(true, true)
        tvStatus.text = "🔌 Connexion..."
        
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    return@launch
                
                socket = device.createRfcommSocketToServiceRecord(BT_UUID)
                socket?.connect()
                writer = PrintWriter(OutputStreamWriter(socket?.outputStream, "UTF-8"), true)
                
                runOnUiThread {
                    tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Déplace ton doigt sur le pavé"
                    updateUI(true, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connexion échouée: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ ÉCHEC: ${e.message}"
                    resetUI()
                }
            }
        }
    }

    private fun send(cmd: String) {
        if (writer == null || socket?.isConnected != true) {
            Toast.makeText(this, "Pas connecté", Toast.LENGTH_SHORT).show()
            return
        }
        writer?.println(cmd)
        Log.d(TAG, "Envoyé: $cmd")
    }

    private fun disconnect() {
        scope.launch {
            try {
                writer?.close()
                socket?.close()
            } catch (e: Exception) {}
            writer = null
            socket = null
            runOnUiThread {
                tvStatus.text = "🔌 Déconnecté"
                resetUI()
            }
        }
    }

    private fun resetUI() { updateUI(false, false); isServer = false; job?.cancel() }
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
        job?.cancel()
        try { socket?.close() } catch (e: Exception) {}
    }
}
