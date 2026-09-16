package com.pcremote.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.ArrayAdapter
import android.widget.Toast
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), ConnectionListener {

    private lateinit var prefs: SharedPreferences
    private var service: RemoteConnectionService? = null
    private var bound = false

    private lateinit var etIp: android.widget.EditText
    private lateinit var etPort: android.widget.EditText
    private lateinit var etPin: android.widget.EditText
    private lateinit var btnDiscover: android.widget.Button
    private lateinit var btnConnect: android.widget.Button
    private lateinit var lvDevices: android.widget.ListView
    private lateinit var tvStatus: android.widget.TextView

    private var discovered: List<DiscoveredDevice> = emptyList()
    private var autoConnectAttempted = false

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            service?.addListener(this@MainActivity)
            bound = true
            tryAutoReconnect()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("pcremote", Context.MODE_PRIVATE)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        etPin = findViewById(R.id.etPin)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnConnect = findViewById(R.id.btnConnect)
        lvDevices = findViewById(R.id.lvDevices)
        tvStatus = findViewById(R.id.tvStatus)

        etIp.setText(prefs.getString("last_ip", ""))
        etPort.setText(prefs.getInt("last_port", 58432).toString())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val serviceIntent = Intent(this, RemoteConnectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        btnDiscover.setOnClickListener { runDiscovery() }
        btnConnect.setOnClickListener { attemptConnect() }

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            val d = discovered[position]
            etIp.setText(d.ip)
            etPort.setText(d.port.toString())
        }
    }

    private fun tryAutoReconnect() {
        if (autoConnectAttempted) return
        autoConnectAttempted = true

        // Le service tourne peut-etre deja en arriere-plan avec une connexion
        // active (app fermee puis rouverte sans que le service ait ete tue) :
        // dans ce cas, on saute directement au tableau de bord.
        if (service?.isConnected == true) {
            startActivity(Intent(this, RemoteControlActivity::class.java))
            finish()
            return
        }

        val ip = prefs.getString("last_ip", null)
        val port = prefs.getInt("last_port", 58432)
        val savedToken = ip?.let { prefs.getString("token_$it", null) }

        if (ip.isNullOrEmpty() || savedToken == null) {
            return  // aucun PC connu -- l'utilisateur doit se connecter manuellement
        }

        tvStatus.text = "Reconnexion automatique a $ip..."
        service?.connect(ip, port, savedToken, null)
    }

    private fun runDiscovery() {
        tvStatus.text = "Recherche du PC sur le reseau..."
        lifecycleScope.launch {
            discovered = DeviceDiscovery.discover()
            val labels = discovered.map { "${it.name} (${it.ip}:${it.port})" }
            lvDevices.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
            tvStatus.text = if (discovered.isEmpty())
                "Aucun PC trouve. Verifiez que server.py tourne et que vous etes sur le meme WiFi."
            else
                "${discovered.size} PC trouve(s). Touchez pour selectionner."
        }
    }

    private fun attemptConnect() {
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 58432
        if (ip.isEmpty()) {
            Toast.makeText(this, "Entrez l'adresse IP du PC", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("last_ip", ip).putInt("last_port", port).apply()

        val savedToken = prefs.getString("token_$ip", null)
        val pin = etPin.text.toString().trim().ifEmpty { null }

        if (savedToken == null && pin == null) {
            Toast.makeText(this, "Entrez le PIN affiche sur le PC", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Connexion en cours..."
        service?.connect(ip, port, savedToken, pin)
    }

    override fun onConnected() {
        runOnUiThread { tvStatus.text = "Connecte, appairage en cours..." }
    }

    override fun onPairResult(success: Boolean, token: String?) {
        runOnUiThread {
            val ip = etIp.text.toString().trim()
            if (success) {
                prefs.edit().putString("last_ip", ip).putInt("last_port", etPort.text.toString().toIntOrNull() ?: 58432)
                    .putString("token_$ip", token).apply()
                tvStatus.text = "Appairage reussi !"
                startActivity(Intent(this, RemoteControlActivity::class.java))
                finish()
            } else {
                // Jeton invalide (ex : le PC a ete reinitialise) -- on l'oublie
                // pour ne pas reessayer en boucle avec un jeton perime.
                if (ip.isNotEmpty()) prefs.edit().remove("token_$ip").apply()
                tvStatus.text = "PIN incorrect ou appairage expire. Entrez le PIN affiche sur le PC."
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            tvStatus.text = "Connexion au PC perdue. Nouvelle tentative automatique en arriere-plan..."
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            tvStatus.text = "PC injoignable ($message). Verifiez qu'il est allume et que le serveur tourne — " +
                "nouvelle tentative automatique en arriere-plan, ou reessayez manuellement ci-dessous."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            service?.removeListener(this)
            unbindService(connection)
        }
    }
}
