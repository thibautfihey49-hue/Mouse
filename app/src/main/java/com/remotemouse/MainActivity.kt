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
import java.nio.charset.StandardCharsets

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
    private lateinit var tvDebug: TextView
    
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
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
        tvDebug = findViewById(R.id.tvDebug)
        
        btnServer.setOnClickListener { startServer() }
        btnScan.setOnClickListener { scanAndConnect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnClick.setOnClickListener { sendCommand("CLICK") }
        
        setupTouchpad()
        updateUI(false)
        tvStatus.text = "✅ Prêt\n\n🌐 Les 2 appareils sur le MÊME WiFi"
        tvFound.text = ""
        tvDebug.text = ""
    }

    private fun debugLog(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            tvDebug.append("$msg\n")
            val scroll = tvDebug.layout
            if (scroll != null) {
                tvDebug.scrollTo(0, scroll.height)
            }
        }
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
                        sendCommand("MOVE|$dx|$dy")
                        cursorX += dx * 0.5f
                        cursorY += dy * 0.5f
                        cursorX = cursorX.coerceIn(10f, 600f)
                        cursorY = cursorY.coerceIn(10f, 400f)
                        runOnUiThread {
                            cursor.x = cursorX
                            cursor.y = cursorY
                        }
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
        tvDebug.text = ""
        
        val ip = getLocalIP()
        val deviceName = Build.MODEL ?: "Serveur"
        
        tvStatus.text = "🟡 SERVEUR EN ÉCOUTE\n\n📱 Appareil: $deviceName\n🌐 WiFi: $ip\n\nEn attente de connexion..."
        debugLog("Serveur démarré sur $ip:$TCP_PORT")
        
        job = scope.launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                serverSocket?.soTimeout = 0
                startUdpBroadcaster(deviceName, ip)
                
                while (isRunning) {
                    try {
                        debugLog("En attente d'un client...")
                        val client = serverSocket?.accept() ?: break
                        debugLog("✅ Client connecté: ${client.inetAddress}")
                        
                        runOnUiThread {
                            tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Utilisez le pavé tactile !"
                            updateUI(true, false)
                            tvFound.text = ""
                        }
                        
                        socket = client
                        output = client.getOutputStream()
                        input = client.getInputStream()
                        
                        // Envoyer un ping de test
                        sendCommand("PING")
                        
                        startReading()
                        
                        while (isRunning && socket?.isConnected == true) {
                            delay(500)
                        }
                        
                        if (isRunning) {
                            debugLog("🔌 Client déconnecté — Réécoute...")
                            cleanupConnection()
                            runOnUiThread {
                                tvStatus.text = "🟡 Déconnecté\nEn attente d'un nouvel appareil..."
                            }
                        }
                    } catch (e: Exception) {
                        debugLog("Erreur accept: ${e.message}")
                        if (isRunning) delay(1000)
                    }
                }
            } catch (e: Exception) {
                debugLog("ERREUR Serveur: ${e.message}")
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
                        val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                        
                        if (msg == BROADCAST_MSG) {
                            val response = "$name|$ip|$TCP_PORT"
                            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                            val senderAddr = packet.address
                            val senderPort = packet.port
                            
                            val responsePacket = DatagramPacket(
                                responseBytes, responseBytes.size,
                                senderAddr, senderPort
                            )
                            udpSocket?.send(responsePacket)
                            debugLog("📤 Répondu à la découverte depuis $senderAddr")
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(500)
                    }
                }
            } catch (e: Exception) {
                debugLog("UDP Erreur: ${e.message}")
            }
        }
    }

    private fun scanAndConnect() {
        isServer = false
        isRunning = true
        updateUI(true, true)
        tvStatus.text = "🔍 RECHERCHE DU SERVEUR...\n\nRecherche en cours..."
        tvFound.text = ""
        tvDebug.text = ""
        
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
                val discoverMsg = BROADCAST_MSG.toByteArray(StandardCharsets.UTF_8)
                
                debugLog("🔍 Envoi de la découverte à $broadcastAddr:$UDP_PORT")
                
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
                        val response = String(responsePacket.data, 0, responsePacket.length, StandardCharsets.UTF_8).trim()
                        
                        debugLog("📩 Réponse reçue: $response")
                        
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
                        debugLog("Erreur réception: ${e.message}")
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
                    debugLog("✅ Un seul serveur trouvé, connexion auto à ${server.address}")
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
                debugLog("Erreur scan: ${e.message}")
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
                tvDebug.text = ""
            }
            
            socket = Socket()
            socket?.tcpNoDelay = true  // ✅ DÉSACTIVE LE BUFFERING — IMMÉDIAT !
            socket?.keepAlive = true
            socket?.connect(InetSocketAddress(ip, port), 10000)
            
            output = socket?.getOutputStream()
            input = socket?.getInputStream()
            
            debugLog("✅ Connecté au serveur $ip")
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
                debugLog("🔌 Déconnecté du serveur")
                runOnUiThread {
                    tvStatus.text = "🔌 Déconnecté\nRecherchez à nouveau..."
                    resetUI()
                }
            }
        } catch (e: Exception) {
            debugLog("❌ Connexion échouée: ${e.message}")
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
                debugLog("📖 Boucle de lecture démarrée")
                val buffer = ByteArray(1024)
                var accumulated = StringBuilder()
                
                while (isRunning && socket?.isConnected == true) {
                    try {
                        val bytesRead = input?.read(buffer) ?: -1
                        
                        if (bytesRead == -1) {
                            debugLog("🔌 Flux fermé par l'autre appareil")
                            break
                        }
                        
                        if (bytesRead > 0) {
                            val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                            accumulated.append(chunk)
                            debugLog("📥 Reçu brut: '$chunk'")
                            
                            // Traiter chaque ligne complète
                            while (accumulated.contains("\n")) {
                                val lineEnd = accumulated.indexOf("\n")
                                val line = accumulated.substring(0, lineEnd).trim()
                                accumulated.delete(0, lineEnd + 1)
                                
                                if (line.isNotEmpty()) {
                                    debugLog("✅ Commande: '$line'")
                                    InputDispatcher.handleCommand(line)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        debugLog("❌ Erreur lecture: ${e.message}")
                        delay(100)
                    }
                }
                debugLog("📖 Boucle de lecture terminée")
            } catch (e: Exception) {
                debugLog("❌ Erreur lecture globale: ${e.message}")
            }
        }
    }

    private fun sendCommand(msg: String) {
        if (output == null || socket?.isConnected != true) {
            debugLog("❌ Pas connecté — impossible d'envoyer")
            runOnUiThread {
                Toast.makeText(this, "Pas connecté", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val fullMsg = "$msg\n"
            val bytes = fullMsg.toByteArray(StandardCharsets.UTF_8)
            output?.write(bytes)
            output?.flush()  // ✅ TRÈS IMPORTANT — ENVOIE TOUT DE SUITE !
            debugLog("📤 Envoyé: '$msg' (${bytes.size} octets)")
        } catch (e: Exception) {
            debugLog("❌ Envoi échoué: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Erreur envoi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            disconnect()
        }
    }

    private fun cleanupConnection() {
        readJob?.cancel()
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (e: Exception) {
            debugLog("Erreur fermeture: ${e.message}")
        }
        input = null
        output = null
        socket = null
    }

    private fun disconnect() {
        isRunning = false
        job?.cancel()
        udpJob?.cancel()
        readJob?.cancel()
        serverSocket?.close()
        udpSocket?.close()
        cleanupConnection()
        tvStatus.text = "🔌 Déconnecté"
        tvFound.text = ""
        tvDebug.text = ""
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
