package com.pcremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

data class AppInfo(val name: String, val path: String)
data class WindowInfo(val hwnd: Long, val title: String, val pid: Int)

interface ConnectionListener {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onPairResult(success: Boolean, token: String?) {}
    fun onAppsList(apps: List<AppInfo>) {}
    fun onWindowsList(windows: List<WindowInfo>) {}
    fun onNotificationReceived(app: String, title: String, message: String) {}
    fun onError(message: String) {}
}

/**
 * Service de premier plan qui maintient la connexion TCP avec le serveur Windows.
 *
 * Toutes les commandes passent par une file d'attente traitee par un seul
 * thread d'ecriture (au lieu de creer un thread par commande) : cela
 * supprime la latence liee a la creation de threads et garde l'ordre des
 * commandes. Les deplacements de souris sont en plus regroupes toutes les
 * 16 ms (~60 images/s) pour un pave tactile fluide et bien synchronise.
 */
class RemoteConnectionService : Service() {

    private val binder = LocalBinder()
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val listeners = CopyOnWriteArrayList<ConnectionListener>()

    private val sendQueue = LinkedBlockingQueue<String>()
    private var senderThread: Thread? = null

    // Deplacement souris regroupe (evite d'inonder le reseau de micro-paquets)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDx = 0f
    private var pendingDy = 0f
    private val moveLock = Any()
    private val moveFlusher = object : Runnable {
        override fun run() {
            var dx = 0f
            var dy = 0f
            synchronized(moveLock) {
                if (pendingDx != 0f || pendingDy != 0f) {
                    dx = pendingDx
                    dy = pendingDy
                    pendingDx = 0f
                    pendingDy = 0f
                }
            }
            if (dx != 0f || dy != 0f) {
                enqueue(JSONObject().put("cmd", "mouse_move").put("dx", dx).put("dy", dy))
            }
            mainHandler.postDelayed(this, 16)
        }
    }

    var isConnected = false
        private set
    var host: String? = null
    var port: Int = 58432
    var token: String? = null

    private var intentionalDisconnect = false
    private val reconnectDelayMs = 3000L

    inner class LocalBinder : Binder() {
        fun getService(): RemoteConnectionService = this@RemoteConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID_SERVICE, buildForegroundNotification())
        mainHandler.post(moveFlusher)
    }

    fun addListener(l: ConnectionListener) { listeners.add(l) }
    fun removeListener(l: ConnectionListener) { listeners.remove(l) }

    fun connect(ip: String, tcpPort: Int, savedToken: String?, pin: String?) {
        intentionalDisconnect = false
        Thread {
            var wasConnected = false
            try {
                socket?.close()
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, tcpPort), 5000)
                socket = s
                host = ip
                port = tcpPort
                writer = PrintWriter(s.getOutputStream(), true)
                isConnected = true
                wasConnected = true
                listeners.forEach { it.onConnected() }

                startSenderThread()

                val pairMsg = JSONObject().put("cmd", "pair").put("device", android.os.Build.MODEL ?: "Android")
                if (savedToken != null) pairMsg.put("token", savedToken)
                if (pin != null) pairMsg.put("pin", pin)
                enqueue(pairMsg)

                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    handleIncoming(line!!)
                }
            } catch (e: Exception) {
                listeners.forEach { it.onError(e.message ?: "Erreur de connexion") }
            } finally {
                isConnected = false
                // Ne signale une "deconnexion" que si une connexion avait bien
                // ete etablie -- sinon onError() suffit et ce message generique
                // n'ecraserait qu'un message plus utile.
                if (wasConnected) listeners.forEach { it.onDisconnected() }
                scheduleReconnectIfNeeded()
            }
        }.start()
    }

    /**
     * Reessaie automatiquement la connexion apres une coupure inattendue
     * (le PC n'a pas ete deliberement deconnecte depuis l'app). Utilise le
     * dernier hote/port/jeton connus, sans redemander le PIN. S'arrete si
     * plus rien ne repond (le serveur Windows a ete arrete) -- les tentatives
     * continuent alors indefiniment en arriere-plan, sans bloquer l'usage
     * de l'app ni redemander le PIN a l'utilisateur des que le serveur
     * revient en ligne.
     */
    private fun scheduleReconnectIfNeeded() {
        if (intentionalDisconnect) return
        val h = host ?: return
        val t = token ?: return
        mainHandler.postDelayed({
            if (!isConnected && !intentionalDisconnect) {
                connect(h, port, t, null)
            }
        }, reconnectDelayMs)
    }

    /** Un seul thread consomme la file et ecrit sur le socket, en ordre. */
    private fun startSenderThread() {
        senderThread?.interrupt()
        senderThread = Thread {
            try {
                while (isConnected || sendQueue.isNotEmpty()) {
                    val line = sendQueue.take()
                    writer?.println(line)
                }
            } catch (e: InterruptedException) {
                // arret normal
            }
        }.also { it.start() }
    }

    private fun enqueue(json: JSONObject) {
        sendQueue.offer(json.toString())
    }

    private fun handleIncoming(line: String) {
        try {
            val obj = JSONObject(line)
            when (obj.optString("type")) {
                "pair_result" -> {
                    val ok = obj.optString("status") == "ok"
                    if (ok) token = obj.optString("token")
                    listeners.forEach { it.onPairResult(ok, token) }
                }
                "apps_list" -> {
                    val arr = obj.getJSONArray("apps")
                    val list = mutableListOf<AppInfo>()
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        list.add(AppInfo(a.getString("name"), a.getString("path")))
                    }
                    listeners.forEach { it.onAppsList(list) }
                }
                "windows_list" -> {
                    val arr = obj.getJSONArray("windows")
                    val list = mutableListOf<WindowInfo>()
                    for (i in 0 until arr.length()) {
                        val w = arr.getJSONObject(i)
                        list.add(WindowInfo(w.getLong("hwnd"), w.getString("title"), w.getInt("pid")))
                    }
                    listeners.forEach { it.onWindowsList(list) }
                }
                "notification" -> {
                    val app = obj.optString("app")
                    val title = obj.optString("title")
                    val message = obj.optString("message")
                    showSystemNotification(app, title, message)
                    listeners.forEach { it.onNotificationReceived(app, title, message) }
                }
            }
        } catch (e: Exception) {
            // ligne non JSON, ignoree
        }
    }

    // ---- Commandes ----

    fun sendPower(action: String) = enqueue(JSONObject().put("cmd", "power").put("action", action))
    fun requestApps() = enqueue(JSONObject().put("cmd", "list_apps"))
    fun launchApp(path: String) = enqueue(JSONObject().put("cmd", "launch_app").put("path", path))

    fun requestWindows() = enqueue(JSONObject().put("cmd", "list_windows"))
    fun closeWindow(hwnd: Long) = enqueue(JSONObject().put("cmd", "close_window").put("hwnd", hwnd))
    fun forceClose(pid: Int) = enqueue(JSONObject().put("cmd", "force_close").put("pid", pid))

    /** Accumule le deplacement ; l'envoi reel est regroupe par moveFlusher (~60 fps). */
    fun moveMouse(dx: Float, dy: Float) {
        synchronized(moveLock) {
            pendingDx += dx
            pendingDy += dy
        }
    }

    fun clickMouse(button: String, action: String) =
        enqueue(JSONObject().put("cmd", "mouse_click").put("button", button).put("action", action))
    fun scrollMouse(amount: Int) = enqueue(JSONObject().put("cmd", "mouse_scroll").put("amount", amount))
    fun sendText(text: String) = enqueue(JSONObject().put("cmd", "key_text").put("text", text))
    fun sendKey(key: String) = enqueue(JSONObject().put("cmd", "key_press").put("key", key))
    fun sendKeyCombo(keys: List<String>) =
        enqueue(JSONObject().put("cmd", "key_combo").put("keys", org.json.JSONArray(keys)))

    fun disconnect() {
        intentionalDisconnect = true
        try { socket?.close() } catch (e: Exception) { }
        isConnected = false
        senderThread?.interrupt()
    }

    private fun buildForegroundNotification(): Notification {
        val channelId = "pcremote_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Connexion PC Remote", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Telecommande PC")
            .setContentText("Service de connexion actif")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    private fun showSystemNotification(app: String, title: String, message: String) {
        val channelId = "pcremote_notifications"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Notifications du PC", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("$app: $title")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        intentionalDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val NOTIF_ID_SERVICE = 1
    }
}
