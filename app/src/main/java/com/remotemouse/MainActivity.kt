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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var serverJob: Job? = null
    private var sender: OutputStreamWriter? = null
    private var isServer = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val TAG = "BluetoothMouse"

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
        touchpad.onTouchListener = { dx, dy -> 
            Log.d(TAG, "Envoi: MOVE|$dx|$dy")
            sendCmd("MOVE|$dx|$dy") 
        }
        btnLeft.setOnClickListener { 
            Log.d(TAG, "Envoi: CLIC")
            sendCmd("CLICK") 
        }
        btnRight.setOnClickListener { 
            Log.d(TAG, "Envoi: RIGHT_CLICK")
            sendCmd("RIGHT_CLICK") 
        }

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
        checkBluetoothEnabled()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 123) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                checkBluetoothEnabled()
            } else {
                Toast.makeText(this, "Permissions Bluetooth requises", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun checkBluetoothEnabled() {
        if (bluetoothAdapter?.isEnabled == true) {
            ready()
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1001)
        }
    }

    private fun ready() {
        tvStatus.text = "✅ Bluetooth prêt\n\n📱 Serveur → Démarrer + activer accessibilité\n📲 Client → Se connecter"
        updateUI(false)
    }

    private fun startServer() {
        if (!checkAccessibility()) {
            Toast.makeText(this, "👉 Étape 1/2 : Active l'accessibilité pour BluetoothMouse", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        isServer = true
        updateUI(true, true)
        
        val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(discoverable)

        tvStatus.text = "⏳ SERVEUR EN ÉCOUTE...\n\nNom: ${bluetoothAdapter?.name}\nEn attente de connexion\n\n⚠️ VÉRIFIE : Accessibilité = ACTIVÉE ✅"

        serverJob = scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Permission requise", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                
                val serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("BluetoothMouse", BLUETOOTH_UUID)
                Log.d(TAG, "Serveur démarré, en attente...")
                
                val clientSocket = serverSocket?.accept()
                serverSocket?.close()
                
                clientSocket?.let {
                    socket = it
                    val device = it.remoteDevice
                    Log.d(TAG, "Connecté à: ${device.name}")
                    
                    runOnUiThread {
                        tvStatus.text = "✅ CONNECTÉ À : ${device.name}\n\n📡 En attente des commandes..."
                        updateUI(true, false)
                    }
                    
                    // LECTURE AMÉLIORÉE — ligne par ligne
                    val reader = BufferedReader(InputStreamReader(it.inputStream))
                    while (true) {
                        try {
                            val cmd = reader.readLine()
                            if (cmd == null) {
                                Log.d(TAG, "Connexion fermée par l'autre appareil")
                                break
                            }
                            Log.d(TAG, "COMMANDE REÇUE: '$cmd'")
                            InputDispatcher.dispatchCommand(cmd)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur lecture: ${e.message}")
                            break
                        }
                    }
                    Log.d(TAG, "Boucle de lecture terminée")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Serveur erreur: ${e.message}", e)
                runOnUiThread {
                    if (!isDestroyed) {
                        tvStatus.text = "❌ Erreur: ${e.message}"
                        resetUI()
                    }
                }
            }
        }
    }

    private fun showPairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val devices = bluetoothAdapter?.bondedDevices ?: emptySet()
        
        if (devices.isEmpty()) {
            Toast.makeText(this, "Aucun appareil associé\n→ Associe d'abord les 2 téléphones dans les paramètres Bluetooth", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return@launch
                }
                socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_UUID)
                socket?.connect()
                sender = OutputStreamWriter(socket?.outputStream, "UTF-8")
                
                runOnUiThread {
                    tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Utilise le pavé tactile ci-dessous"
                    updateUI(true, false)
                }
                Log.d(TAG, "Client connecté, prêt à envoyer")
            } catch (e: Exception) {
                Log.e(TAG, "Connexion échouée: ${e.message}", e)
                runOnUiThread {
                    tvStatus.text = "❌ ÉCHEC\n\n• Le serveur est-il démarré ?\n• Les 2 appareils sont-ils associés ?\n• Erreur: ${e.message}"
                    resetUI()
                }
            }
        }
    }

    private fun sendCmd(cmd: String): Boolean {
        if (sender == null || socket?.isConnected != true) {
            Log.w(TAG, "Pas connecté ou writer null")
            return false
        }
        return try {
            sender?.write("$cmd\n")
            sender?.flush()
            Log.d(TAG, "Envoyé: $cmd")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Envoi échoué: ${e.message}")
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
        val result = enabled?.contains(packageName) == true
        Log.d(TAG, "Accessibilité: $result")
        return result
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) {
                ready()
            } else {
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
    
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                    onTouchListener?.invoke(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }
        }
        return true
    }
}
