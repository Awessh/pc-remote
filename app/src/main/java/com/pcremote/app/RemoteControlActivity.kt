package com.pcremote.app

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class RemoteControlActivity : AppCompatActivity(), ConnectionListener {

    private var service: RemoteConnectionService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            service?.addListener(this@RemoteControlActivity)
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)

        bindService(Intent(this, RemoteConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)

        // Cartes principales
        findViewById<android.view.View>(R.id.cardTouchpad).setOnClickListener {
            startActivity(Intent(this, TouchpadKeyboardActivity::class.java))
        }
        findViewById<android.view.View>(R.id.cardApps).setOnClickListener {
            startActivity(Intent(this, AppsListActivity::class.java))
        }
        findViewById<android.view.View>(R.id.cardWindows).setOnClickListener {
            startActivity(Intent(this, RunningWindowsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.cardPower).setOnClickListener {
            showPowerMenu()
        }

        // Multimedia
        findViewById<android.widget.Button>(R.id.btnVolDown).setOnClickListener { service?.sendKey("volume_down") }
        findViewById<android.widget.Button>(R.id.btnVolMute).setOnClickListener { service?.sendKey("volume_mute") }
        findViewById<android.widget.Button>(R.id.btnVolUp).setOnClickListener { service?.sendKey("volume_up") }
        findViewById<android.widget.Button>(R.id.btnMediaPrev).setOnClickListener { service?.sendKey("media_prev") }
        findViewById<android.widget.Button>(R.id.btnMediaPlay).setOnClickListener { service?.sendKey("media_play_pause") }
        findViewById<android.widget.Button>(R.id.btnMediaNext).setOnClickListener { service?.sendKey("media_next") }
    }

    private fun showPowerMenu() {
        val options = arrayOf(
            "Verrouiller" to "lock",
            "Se deconnecter" to "logoff",
            "Mettre en veille" to "sleep",
            "Veille prolongee" to "hibernate",
            "Redemarrer" to "restart",
            "Eteindre" to "shutdown"
        )
        AlertDialog.Builder(this)
            .setTitle("Alimentation")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val (label, action) = options[which]
                confirmAndSend("$label le PC ?", action)
            }
            .show()
    }

    private fun confirmAndSend(message: String, action: String) {
        AlertDialog.Builder(this)
            .setTitle("Confirmation")
            .setMessage(message)
            .setPositiveButton("Oui") { _, _ ->
                service?.sendPower(action)
                Toast.makeText(this, "Commande envoyee", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onNotificationReceived(app: String, title: String, message: String) {
        // Deja affichee par le service via une notification systeme
    }

    override fun onDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "Connexion au PC perdue — reconnexion automatique...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnected() {
        runOnUiThread {
            Toast.makeText(this, "Reconnecte au PC", Toast.LENGTH_SHORT).show()
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
