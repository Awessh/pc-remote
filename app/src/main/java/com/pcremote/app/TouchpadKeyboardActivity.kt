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
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Ecran principal de controle : pave tactile pour la souris, et une icone
 * clavier flottante qui affiche/masque le clavier Android pour taper
 * directement. Les deux sont sur le meme ecran pour eviter les allers-retours.
 */
class TouchpadKeyboardActivity : AppCompatActivity() {

    private var service: RemoteConnectionService? = null
    private var bound = false
    private var suppressWatcher = false
    private var keyboardVisible = false

    // Sensibilite du deplacement (ajuster si trop rapide/lent)
    private val sensitivity = 1.8f

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pointerCount = 1

    private lateinit var hiddenInput: android.widget.EditText

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_touchpad_keyboard)

        bindService(Intent(this, RemoteConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)

        val pad = findViewById<android.view.View>(R.id.touchpadArea)
        pad.setOnTouchListener { _, event -> handleTouch(event); true }

        findViewById<android.widget.Button>(R.id.btnLeftClick).setOnClickListener {
            service?.clickMouse("left", "click")
        }
        findViewById<android.widget.Button>(R.id.btnRightClick).setOnClickListener {
            service?.clickMouse("right", "click")
        }

        hiddenInput = findViewById(R.id.etHiddenInput)
        setupHiddenInput()

        findViewById<android.widget.ImageButton>(R.id.fabKeyboard).setOnClickListener {
            toggleKeyboard()
        }

        setupShortcuts()
    }

    // ---- Pave tactile ----

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                pointerCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointerCount = event.pointerCount
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) * sensitivity
                val dy = (event.y - lastY) * sensitivity
                if (kotlin.math.abs(dx) > 0.3f || kotlin.math.abs(dy) > 0.3f) {
                    if (pointerCount >= 2) {
                        service?.scrollMouse((-dy / 8).toInt())
                    } else {
                        service?.moveMouse(dx, dy)
                        moved = true
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && pointerCount == 1) {
                    service?.clickMouse("left", "click")
                }
            }
        }
    }

    // ---- Clavier a la demande ----

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(hiddenInput.windowToken, 0)
            keyboardVisible = false
        } else {
            hiddenInput.requestFocus()
            imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT)
            keyboardVisible = true
        }
    }

    private fun setupHiddenInput() {
        hiddenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressWatcher) return
                if (count > before) {
                    val added = s?.subSequence(start + before, start + count)?.toString() ?: ""
                    if (added.isNotEmpty()) service?.sendText(added)
                } else if (before > count) {
                    repeat(before - count) { service?.sendKey("backspace") }
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (s != null && s.length > 200) {
                    suppressWatcher = true
                    s.clear()
                    suppressWatcher = false
                }
            }
        })
        hiddenInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                service?.sendKey("enter"); true
            } else false
        }
        hiddenInput.setOnEditorActionListener { _, _, _ ->
            service?.sendKey("enter"); true
        }
    }

    // ---- Raccourcis rapides ----

    private fun setupShortcuts() {
        findViewById<android.widget.Button>(R.id.btnEsc).setOnClickListener { service?.sendKey("esc") }
        findViewById<android.widget.Button>(R.id.btnTab).setOnClickListener { service?.sendKey("tab") }
        findViewById<android.widget.Button>(R.id.btnDel).setOnClickListener { service?.sendKey("delete") }
        findViewById<android.widget.Button>(R.id.btnEnter2).setOnClickListener { service?.sendKey("enter") }
        findViewById<android.widget.Button>(R.id.btnCopy).setOnClickListener { service?.sendKeyCombo(listOf("ctrl", "c")) }
        findViewById<android.widget.Button>(R.id.btnPaste).setOnClickListener { service?.sendKeyCombo(listOf("ctrl", "v")) }
        findViewById<android.widget.Button>(R.id.btnUndo).setOnClickListener { service?.sendKeyCombo(listOf("ctrl", "z")) }
        findViewById<android.widget.Button>(R.id.btnSelectAll).setOnClickListener { service?.sendKeyCombo(listOf("ctrl", "a")) }
        findViewById<android.widget.Button>(R.id.btnAltTab).setOnClickListener { service?.sendKeyCombo(listOf("alt", "tab")) }
        findViewById<android.widget.Button>(R.id.btnWinD).setOnClickListener { service?.sendKeyCombo(listOf("win", "d")) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(connection)
    }
}
