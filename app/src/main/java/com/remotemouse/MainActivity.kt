package com.remotemouse

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnServer: Button
    private lateinit var btnConnect: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnDisconnect: Button

    private val BLUETOOTH_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null
    private var serverJob: Job? = null
    private var sender: OutputStreamWriter? = null
    private var isServer = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnServer = findViewById(R.id.btnServer)
        btnConnect = findViewById(R.id.btnConnect)
        touchpad = findViewById(R.id.touchpad)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        btnServer.setOnClickListener { startServer() }
        btnConnect.setOnClickListener { showPairedDevices() }
        btnDisconnect.setOnClickListener { disconnect() }
        touchpad.onTouchListener = { dx, dy -> sendCmd("MOVE|$dx|$dy") }
        btnLeft.setOnClickListener { sendCmd("CLICK") }
        btnRight.setOnClickListener { sendCmd("RIGHT_CLICK") }

        initBluetooth()
    }

    private fun initBluetooth() {
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager.adapter
        
        when {
            bluetoothAdapter == null -> {
                Toast.makeText(this, "Bluetooth non disponible", Toast.LENGTH_SHORT).show()
                finish()
            }
            !bluetoothAdapter!!.isEnabled -> {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, 1001)
            }
            else -> checkPermissions()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 123)
                return
            }
        }
        ready()
    }

    private fun ready() {
        tvStatus.text = "✅ Bluetooth prêt\n\n📱 Sur l'appareil à CONTRÔLER :\n→ Démarrer le serveur\n\n📲 Sur l'appareil qui CONTRÔLE :\n→ Associer en Bluetooth puis se connecter"
        updateUI(false)
    }

    private fun startServer() {
        if (!checkAccessibility()) {
            Toast.makeText(this, "👉 Active l'accessibilité pour BluetoothMouse", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        isServer = true
        updateUI(true, true)
        
        // Rendre visible
        val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(discoverable)

        tvStatus.text = "⏳ SERVEUR EN ÉCOUTE\n\nNom: ${bluetoothAdapter?.name}\nEn attente de connexion..."

        serverJob = scope.launch {
            try {
                checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, 124)
                val serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("BluetoothMouse", BLUETOOTH_UUID)
                val clientSocket = serverSocket?.accept()
                serverSocket?.close()
                
                clientSocket?.let {
                    socket = it
                    val device = it.remoteDevice
                    connectedDevice = device
                    
                    runOnUiThread {
                        tvStatus.text = "✅ CONNECTÉ À : ${device.name}\n\nUtilise le pavé tactile !"
                        updateUI(true, false)
                    }
                    
                    // Lire les commandes
                    val reader = BufferedReader(InputStreamReader(it.inputStream))
                    while (true) {
                        val cmd = reader.readLine() ?: break
                        Log.d("BluetoothMouse", "Reçu: $cmd")
                        runOnUiThread {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                InputDispatcher.dispatchCommand(cmd)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BluetoothMouse", "Serveur: ${e.message}")
                runOnUiThread {
                    if (!isDestroyed) {
                        tvStatus.text = "❌ Erreur serveur: ${e.message}"
                        resetUI()
                    }
                }
            }
        }
    }

    private fun showPairedDevices() {
        checkPermission(Manifest.permission.BLUETOOTH_CONNECT, 125)
        val devices = bluetoothAdapter?.bondedDevices ?: emptySet()
        
        if (devices.isEmpty()) {
            Toast.makeText(this, "Aucun appareil associé\n→ Associe d'abord les 2 téléphones dans les paramètres Bluetooth", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)
            return
        }

        val deviceNames = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        val deviceList = devices.toList()

        AlertDialog.Builder(this)
            .setTitle("Choisis un appareil")
            .setItems(deviceNames) { _, which ->
                connectToDevice(deviceList[which])
            }
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        isServer = false
        updateUI(true, true)
        tvStatus.text = "🔌 Connexion à ${device.name}..."

        scope.launch {
            try {
                checkPermission(Manifest.permission.BLUETOOTH_CONNECT, 125)
                socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_UUID)
                socket?.connect()
                
                sender = OutputStreamWriter(socket?.outputStream, "UTF-8")
                
                runOnUiThread {
                    tvStatus.text = "✅ CONNECTÉ !\n\nUtilise le pavé tactile ci-dessous"
                    updateUI(true, false)
                }
            } catch (e: Exception) {
                Log.e("BluetoothMouse", "Connexion: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ ÉCHEC\n\n• Le serveur est-il démarré ?\n• Les 2 appareils sont-ils associés en Bluetooth ?\n• Erreur: ${e.message}"
                    resetUI()
                }
            }
        }
    }

    private fun sendCmd(cmd: String): Boolean {
        if (sender == null || socket?.isConnected != true) return false
        return try {
            sender?.write("$cmd\n")
            sender?.flush()
            true
        } catch (e: Exception) {
            Log.e("BluetoothMouse", "Envoi: ${e.message}")
            disconnect()
            false
        }
    }

    private fun disconnect() {
        scope.launch {
            try {
                sender?.close()
                socket?.close()
            } catch (e: Exception) {}
            sender = null
            socket = null
            isServer = false
            runOnUiThread {
                tvStatus.text = "🔌 Déconnecté"
                resetUI()
            }
        }
    }

    private fun resetUI() {
        updateUI(false, false)
        isServer = false
        serverJob?.cancel()
    }

    private fun updateUI(connected: Boolean, connecting: Boolean = false) {
        btnServer.isEnabled = !connected && !connecting
        btnConnect.isEnabled = !connected && !connecting
        btnDisconnect.isEnabled = connected
        touchpad.isEnabled = connected
        btnLeft.isEnabled = connected
        btnRight.isEnabled = connected
    }

    private fun checkAccessibility(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    private fun checkPermission(perm: String, code: Int) {
        if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), code)
            throw SecurityException("Permission requise: $perm")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) checkPermissions()
            else {
                Toast.makeText(this, "Bluetooth requis", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverJob?.cancel()
        try { socket?.close() } catch (e: Exception) {}
    }
}

class TouchpadView(context: android.content.Context, attrs: android.util.AttributeSet) : android.view.View(context, attrs) {
    var onTouchListener: ((dx: Float, dy: Float) -> Unit)? = null
    private var lastX = 0f
    private var lastY = 0f
    
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                    onTouchListener?.invoke(dx, dy)
                    lastX = e.x
                    lastY = e.y
                }
            }
        }
        return true
    }
}
