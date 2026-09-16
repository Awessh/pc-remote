package com.pcremote.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Capture la saisie du clavier Android (y compris les claviers avec
 * autocorrection/emoji) et la transmet au PC caractere par caractere.
 * Le champ est vide en permanence : on n'affiche jamais de texte a l'ecran,
 * on se contente de detecter les insertions/suppressions.
 */
class KeyboardActivity : AppCompatActivity() {

    private var service: RemoteConnectionService? = null
    private var bound = false
    private var suppressWatcher = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard)

        bindService(Intent(this, RemoteConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)

        val input = findViewById<android.widget.EditText>(R.id.etRemoteInput)
        input.requestFocus()
        showKeyboard(input)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressWatcher) return
                if (count > before) {
                    // caracteres ajoutes
                    val added = s?.subSequence(start + before, start + count)?.toString() ?: ""
                    if (added.isNotEmpty()) service?.sendText(added)
                } else if (before > count) {
                    // caracteres supprimes -> backspace
                    repeat(before - count) { service?.sendKey("backspace") }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                // On vide le champ regulierement pour eviter qu'il grossisse indefiniment
                if (s != null && s.length > 200) {
                    suppressWatcher = true
                    s.clear()
                    suppressWatcher = false
                }
            }
        })

        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> { service?.sendKey("enter"); true }
                    else -> false
                }
            } else false
        }

        input.setOnEditorActionListener { _, _, _ ->
            service?.sendKey("enter")
            true
        }

        findViewById<android.widget.Button>(R.id.btnEnter).setOnClickListener { service?.sendKey("enter") }
        findViewById<android.widget.Button>(R.id.btnBackspace).setOnClickListener { service?.sendKey("backspace") }
        findViewById<android.widget.Button>(R.id.btnEsc).setOnClickListener { service?.sendKey("esc") }
        findViewById<android.widget.Button>(R.id.btnTab).setOnClickListener { service?.sendKey("tab") }
    }

    private fun showKeyboard(view: android.view.View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(connection)
    }
}
