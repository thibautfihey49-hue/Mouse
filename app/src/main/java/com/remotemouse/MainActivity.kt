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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var clientSocket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var udpSocket: DatagramSocket? = null
    
    private val isServerMode = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var readJob: Job? = null
    private var udpJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
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
                            cursor.translationX = cursorX
                            cursor.translationY = cursorY
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
        
        isServerMode.set(true)
        isRunning.set(true)
        updateUI(true, true)
        tvDebug.text = ""
        
        val ip = getLocalIP()
        val deviceName = Build.MODEL ?: "Serveur"
        
        tvStatus.text = "🟡 SERVEUR EN ÉCOUTE\n\n📱 Appareil: $deviceName\n🌐 WiFi: $ip\n\nEn attente de connexion..."
        debugLog("🚀 Serveur démarré sur $ip:$TCP_PORT")
        
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                serverSocket!!.soTimeout = 0
                startUdpBroadcaster(deviceName, ip)
                
                while (isRunning.get()) {
                    try {
                        debugLog("⏳ En attente d'un client...")
                        val client = serverSocket!!.accept()
                        debugLog("✅ Client connecté: ${client.inetAddress}")
                        
                        runOnUiThread {
                            tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Utilisez le pavé tactile !"
                            updateUI(true, false)
                            tvFound.text = ""
                        }
                        
                        setupConnection(client)
                        startReadingLoop()
                        
                        while (isRunning.get() && connected.get()) {
                            delay(1000)
                        }
                        
                        if (isRunning.get()) {
                            debugLog("🔌 Client déconnecté — Réécoute...")
                            cleanupConnection()
                            runOnUiThread {
                                tvStatus.text = "🟡 Déconnecté\nEn attente d'un nouvel appareil..."
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            debugLog("⚠️ Erreur accept: ${e.message}")
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                debugLog("❌ ERREUR Serveur: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ Erreur: ${e.message}"
                    resetUI()
                }
            }
        }
    }

    private fun setupConnection(sock: Socket) {
        clientSocket = sock
        connected.set(true)
        output = PrintWriter(BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)), true)
        input = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
        debugLog("🔗 Connexion établie — flux initialisés")
    }

    private fun startUdpBroadcaster(name: String, ip: String) {
        udpJob?.cancel()
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
                udpSocket!!.broadcast = true
                val buffer = ByteArray(1024)
                
                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket!!.receive(packet)
                        val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                        
                        if (msg == BROADCAST_MSG) {
                            val response = "$name|$ip|$TCP_PORT"
                            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                            val senderAddr = packet.address
                            val senderPort = packet.port
                            
                            val responsePacket = DatagramPacket(responseBytes, responseBytes.size, senderAddr, senderPort)
                            udpSocket!!.send(responsePacket)
                            debugLog("📤 Répondu à ${senderAddr.hostName}")
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) delay(500)
                    }
                }
            } catch (e: Exception) {
                debugLog("⚠️ UDP Erreur: ${e.message}")
            }
        }
    }

    private fun scanAndConnect() {
        isServerMode.set(false)
        isRunning.set(true)
        updateUI(true, true)
        tvStatus.text = "🔍 RECHERCHE DU SERVEUR..."
        tvFound.text = ""
        tvDebug.text = ""
        
        clientJob = scope.launch {
            val foundServers = mutableListOf<DiscoveredServer>()
            
            try {
                val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiLock = wifiMgr.createMulticastLock("WiFiMouse")
                wifiLock.setReferenceCounted(true)
                wifiLock.acquire()
                
                udpSocket = DatagramSocket()
                udpSocket!!.soTimeout = 3000
                
                val broadcastAddr = getBroadcastAddress()
                val discoverMsg = BROADCAST_MSG.toByteArray(StandardCharsets.UTF_8)
                
                debugLog("🔍 Recherche sur $broadcastAddr:$UDP_PORT")
                
                repeat(3) {
                    val packet = DatagramPacket(discoverMsg, discoverMsg.size, InetAddress.getByName(broadcastAddr), UDP_PORT)
                    udpSocket!!.send(packet)
                    delay(500)
                }
                
                val startTime = System.currentTimeMillis()
                val buffer = ByteArray(1024)
                
                while (System.currentTimeMillis() - startTime < 5000 && isRunning.get()) {
                    try {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        udpSocket!!.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length, StandardCharsets.UTF_8).trim()
                        
                        debugLog("📩 Réponse: $response")
                        
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
                        debugLog("⚠️ Erreur réception: ${e.message}")
                    }
                }
                
                wifiLock.release()
                udpSocket!!.close()
                
                if (foundServers.isEmpty()) {
                    runOnUiThread {
                        tvStatus.text = "❌ AUCUN SERVEUR TROUVÉ"
                        resetUI()
                    }
                } else if (foundServers.size == 1) {
                    val server = foundServers[0]
                    debugLog("✅ Connexion auto à ${server.address}:${server.port}")
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
                                if (!connected.get()) {
                                    resetUI()
                                    tvStatus.text = "🔍 Recherche annulée"
                                }
                            }
                            .show()
                    }
                }
            } catch (e: Exception) {
                debugLog("❌ Erreur scan: ${e.message}")
                runOnUiThread {
                    tvStatus.text = "❌ Erreur: ${e.message}"
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
        return String.format("%d.%d.%d.%d", broadcast and 0xFF, broadcast shr 8 and 0xFF, broadcast shr 16 and 0xFF, broadcast shr 24 and 0xFF)
    }

    private suspend fun connectTo(ip: String, port: Int) {
        try {
            runOnUiThread {
                tvStatus.text = "🔌 Connexion à $ip..."
            }
            
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(ip, port), 10000)
            
            setupConnection(sock)
            debugLog("✅ Connecté à $ip:$port")
            
            runOnUiThread {
                tvStatus.text = "✅ CONNECTÉ !\n\n🖱️ Déplacez votre doigt sur le pavé"
                updateUI(true, false)
                tvFound.text = ""
            }
            
            startReadingLoop()
            
            while (isRunning.get() && connected.get()) {
                delay(1000)
            }
            
            if (isRunning.get()) {
                debugLog("🔌 Déconnecté")
                runOnUiThread {
                    tvStatus.text = "🔌 Déconnecté\nRecherchez à nouveau..."
                    resetUI()
                }
            }
        } catch (e: Exception) {
            debugLog("❌ Connexion échouée: ${e.message}")
            runOnUiThread {
                tvStatus.text = "❌ ÉCHEC: ${e.message}"
                resetUI()
            }
        }
    }

    private fun startReadingLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            debugLog("📖 Boucle de lecture DÉMARRÉE")
            try {
                while (isRunning.get() && connected.get() && input != null) {
                    try {
                        val line = input!!.readLine()
                        if (line == null) {
                            debugLog("🔌 Ligne NULL = connexion fermée par l'autre appareil")
                            break
                        }
                        if (line.isNotEmpty()) {
                            debugLog("📥 Commande reçue: '$line'")
                            InputDispatcher.handleCommand(line)
                        }
                    } catch (e: Exception) {
                        debugLog("⚠️ Erreur lecture: ${e.message}")
                        delay(200)
                    }
                }
            } catch (e: Exception) {
                debugLog("❌ Erreur lecture boucle: ${e.message}")
            } finally {
                debugLog("📖 Boucle de lecture TERMINÉE")
                connected.set(false)
            }
        }
    }

    private fun sendCommand(msg: String) {
        if (!connected.get() || output == null) {
            debugLog("❌ Pas connecté — impossible d'envoyer")
            runOnUiThread {
                Toast.makeText(this, "Pas connecté", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            output!!.println(msg)
            output!!.flush()
            debugLog("📤 Envoyé: '$msg'")
        } catch (e: Exception) {
            debugLog("❌ Envoi échoué: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Erreur envoi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            disconnect()
        }
    }

    private fun cleanupConnection() {
        connected.set(false)
        readJob?.cancel()
        try {
            input?.close()
            output?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            debugLog("⚠️ Erreur fermeture: ${e.message}")
        }
        input = null
        output = null
        clientSocket = null
    }

    private fun disconnect() {
        isRunning.set(false)
        connected.set(false)
        serverJob?.cancel()
        clientJob?.cancel()
        udpJob?.cancel()
        readJob?.cancel()
        try {
            serverSocket?.close()
            udpSocket?.close()
        } catch (e: Exception) {}
        cleanupConnection()
        tvStatus.text = "🔌 Déconnecté"
        tvFound.text = ""
        tvDebug.text = ""
        resetUI()
    }

    private fun resetUI() { updateUI(false, false) }
    private fun updateUI(connectedState: Boolean, connecting: Boolean = false) {
        btnServer.isEnabled = !connectedState && !connecting
        btnScan.isEnabled = !connectedState && !connecting
        btnDisconnect.isEnabled = connectedState
        touchpad.isEnabled = connectedState
        btnClick.isEnabled = connectedState
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
