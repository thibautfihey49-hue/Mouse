package com.remotemouse

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class MainActivity : AppCompatActivity() {
    private val TAG = "WiFiMouse"
    private val TCP_PORT = 8888
    private val UDP_PORT = 8889
    private val BROADCAST_MSG = "WIFIMOUSE_SERVER_DISCOVER"
    
    private lateinit var tvStatus: TextView
    private lateinit var btnServer: Button
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var touchpad: View
    private lateinit var btnClick: Button
    private lateinit var cursor: View
    private lateinit var tvFound: TextView
    
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var udpSocket: DatagramSocket? = null
    private var isServer = false
    private var isRunning = false
    private var job: Job? = null
    private var readJob: Job? = null
    private var udpJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var cursorX = 0f
    private var cursorY = 0f
    
    data class DiscoveredServer(val name: String, val address: String, val port: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        tvStatus = findViewById(R.id.tvStatus)
        btnServer = findViewById(R.id.btnServer)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        touchpad = findViewById(R.id.touchpad)
        btnClick = findViewById(R.id.btnClick)
        cursor = findViewById(R.id.cursor)
        tvFound = findViewById(R.id.tvFound)
        
        btnServer.setOnClickListener { startServer() }
        btnScan.setOnClickListener { scanAndConnect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnClick.setOnClickListener { send("CLICK") }
        
        setupTouchpad()
        updateUI(false)
        tvStatus.text = "✅ Prêt\n\n🌐 Les 2 appareils sur le MÊME WiFi"
        tvFound.text = ""
    }

    private fun getLocalIP(): String {
        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiMgr.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xFF,
            ip shr 8 and 0xFF,
            ip shr 16 and 0xFF,
            ip shr 24 and 0xFF
        )
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

    private fun startServer() {
        if (!isAccessibilityOn()) {
            Toast.makeText(this, "👉 Active l'accessibilité dans les paramètres", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        
        isServer = true
        isRunning = true
        updateUI(true, true)
        
        val ip = getLocalIP()
        val deviceName = Build.MODEL ?: "Serveur"
        
        tvStatus.text = "🟡 SERVEUR EN ÉCOUTE\n\n📱 Appareil: $deviceName\n🌐 WiFi: $ip\n\nEn attente de connexion..."
        Log.d(TAG, "Serveur démarré sur $ip:$TCP_PORT")
        
        job = scope.launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                serverSocket?.soTimeout = 0
                startUdpBroadcaster(deviceName, ip)
                
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        Log.d(TAG, "✅ Client connecté: ${client.inetAddress}")
                        
                        runOnUiThread {
                            tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Utilisez le pavé tactile !"
                            updateUI(true, false)
                            tvFound.text = ""
                        }
                        
                        socket = client
                        output = PrintWriter(client.getOutputStream().bufferedWriter(), true)
                        input = BufferedReader(InputStreamReader(client.getInputStream()))
                        
                        startReading()
                        
                        while (isRunning && socket?.isConnected == true) {
                            delay(500)
                        }
                        
                        if (isRunning) {
                            Log.d(TAG, "🔌 Client déconnecté — Réécoute...")
                            cleanupConnection()
                            runOnUiThread {
                                tvStatus.text = "🟡 Déconnecté\nEn attente d'un nouvel appareil..."
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(1000)
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

    private fun startUdpBroadcaster(name: String, ip: String) {
        udpJob?.cancel()
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
                udpSocket?.broadcast = true
                val buffer = ByteArray(1024)
                
                while (isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        
                        if (msg == BROADCAST_MSG) {
                            val response = "$name|$ip|$TCP_PORT"
                            val responseBytes = response.toByteArray()
                            val senderAddr = packet.address
                            val senderPort = packet.port
                            
                            val responsePacket = DatagramPacket(
                                responseBytes, responseBytes.size,
                                senderAddr, senderPort
                            )
                            udpSocket?.send(responsePacket)
                            Log.d(TAG, "📤 Répondu à la découverte depuis $senderAddr")
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP Erreur: ${e.message}")
            }
        }
    }

    private fun scanAndConnect() {
        isServer = false
        isRunning = true
        updateUI(true, true)
        tvStatus.text = "🔍 RECHERCHE DU SERVEUR...\n\nRecherche en cours..."
        tvFound.text = ""
        
        scope.launch {
            val foundServers = mutableListOf<DiscoveredServer>()
            
            try {
                val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiLock = wifiMgr.createMulticastLock("WiFiMouse")
                wifiLock.setReferenceCounted(true)
                wifiLock.acquire()
                
                udpSocket = DatagramSocket()
                udpSocket?.soTimeout = 3000
                
                val broadcastAddr = getBroadcastAddress()
                val discoverMsg = BROADCAST_MSG.toByteArray()
                
                Log.d(TAG, "🔍 Envoi de la découverte à $broadcastAddr:$UDP_PORT")
                
                repeat(3) {
                    val packet = DatagramPacket(
                        discoverMsg, discoverMsg.size,
                        InetAddress.getByName(broadcastAddr), UDP_PORT
                    )
                    udpSocket?.send(packet)
                    delay(500)
                }
                
                val startTime = System.currentTimeMillis()
                val buffer = ByteArray(1024)
                
                while (System.currentTimeMillis() - startTime < 5000 && isRunning) {
                    try {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length).trim()
                        
                        Log.d(TAG, "📩 Réponse reçue: $response")
                        
                        val parts = response.split("|")
                        if (parts.size == 3) {
                            val server = DiscoveredServer(parts[0], parts[1], parts[2].toInt())
                            if (!foundServers.any { it.address == server.address }) {
                                foundServers.add(server)
                                runOnUiThread {
                                    tvFound.text = "✅ Trouvé: ${server.name}\n${server.address}"
                                }
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        Log.d(TAG, "Erreur réception: ${e.message}")
                    }
                }
                
                wifiLock.release()
                udpSocket?.close()
                
                if (foundServers.isEmpty()) {
                    runOnUiThread {
                        tvStatus.text = "❌ AUCUN SERVEUR TROUVÉ\n\n• Les 2 appareils sont-ils au MÊME WiFi ?\n• Le serveur est-il démarré ?"
                        resetUI()
                    }
                } else if (foundServers.size == 1) {
                    val server = foundServers[0]
                    Log.d(TAG, "✅ Un seul serveur trouvé, connexion auto à ${server.address}")
                    connectTo(server.address, server.port)
                } else {
                    runOnUiThread {
                        val names = foundServers.map { "${it.name}\n${it.address}" }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Sélectionnez l'appareil")
                            .setItems(names) { _, i ->
                                val srv = foundServers[i]
                                scope.launch { connectTo(srv.address, srv.port) }
                            }
                            .setOnDismissListener {
                                if (socket?.isConnected != true) {
                                    resetUI()
                                    tvStatus.text = "🔍 Recherche annulée"
                                }
                            }
                            .show()
                        tvStatus.text = "✅ ${foundServers.size} appareil(s) trouvé(s)"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur scan: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ Erreur recherche: ${e.message}"
                    resetUI()
                }
            }
        }
    }

    private fun getBroadcastAddress(): String {
        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiMgr.dhcpInfo
        val ip = dhcpInfo.ipAddress
        val mask = dhcpInfo.netmask
        
        val broadcast = ip or mask.inv()
        return String.format(
            "%d.%d.%d.%d",
            broadcast and 0xFF,
            broadcast shr 8 and 0xFF,
            broadcast shr 16 and 0xFF,
            broadcast shr 24 and 0xFF
        )
    }

    private suspend fun connectTo(ip: String, port: Int) {
        try {
            runOnUiThread {
                tvStatus.text = "🔌 Connexion à $ip..."
            }
            
            socket = Socket()
            socket?.connect(InetSocketAddress(ip, port), 10000)
            
            output = PrintWriter(socket?.getOutputStream()?.bufferedWriter(), true)
            input = BufferedReader(InputStreamReader(socket?.getInputStream()))
            
            Log.d(TAG, "✅ Connecté au serveur $ip")
            runOnUiThread {
                tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Déplacez votre doigt sur le pavé"
                updateUI(true, false)
                tvFound.text = ""
            }
            
            startReading()
            
            while (isRunning && socket?.isConnected == true) {
                delay(500)
            }
            
            if (isRunning) {
                Log.d(TAG, "🔌 Déconnecté du serveur")
                runOnUiThread {
                    tvStatus.text = "🔌 Déconnecté\nRecherchez à nouveau..."
                    resetUI()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connexion échouée: ${e.message}")
            runOnUiThread {
                tvStatus.text = "❌ ÉCHEC DE CONNEXION\n\n${e.message}"
                resetUI()
            }
        }
    }

    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch {
            try {
                while (isRunning && socket?.isConnected == true) {
                    val line = input?.readLine() ?: break
                    if (line.isNotEmpty()) {
                        Log.d(TAG, "📥 Commande: $line")
                        InputDispatcher.handleCommand(line)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Lecture arrêtée: ${e.message}")
            }
        }
    }

    private fun send(msg: String) {
        if (output == null || socket?.isConnected != true) {
            Toast.makeText(this, "Pas connecté — appuyez sur 'Rechercher'", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            output?.println(msg)
            Log.d(TAG, "📤 Envoyé: $msg")
        } catch (e: Exception) {
            Log.e(TAG, "Envoi échoué: ${e.message}")
            disconnect()
        }
    }

    private fun cleanupConnection() {
        readJob?.cancel()
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (e: Exception) {}
        input = null
        output = null
        socket = null
    }

    private fun disconnect() {
        isRunning = false
        job?.cancel()
        udpJob?.cancel()
        serverSocket?.close()
        udpSocket?.close()
        cleanupConnection()
        tvStatus.text = "🔌 Déconnecté"
        tvFound.text = ""
        resetUI()
    }

    private fun resetUI() { updateUI(false, false) }
    private fun updateUI(connected: Boolean, connecting: Boolean = false) {
        btnServer.isEnabled = !connected && !connecting
        btnScan.isEnabled = !connected && !connecting
        btnDisconnect.isEnabled = connected
        touchpad.isEnabled = connected
        btnClick.isEnabled = connected
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        job?.cancel()
        udpJob?.cancel()
        readJob?.cancel()
        serverSocket?.close()
        udpSocket?.close()
        cleanupConnection()
    }
}
