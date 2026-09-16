package com.pcremote.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity

class TouchpadActivity : AppCompatActivity() {

    private var service: RemoteConnectionService? = null
    private var bound = false

    // Sensibilite du deplacement (ajuster si trop rapide/lent)
    private val sensitivity = 1.6f

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pointerCount = 1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_touchpad)

        bindService(Intent(this, RemoteConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)

        val pad = findViewById<android.view.View>(R.id.touchpadArea)
        pad.setOnTouchListener { _, event -> handleTouch(event); true }

        findViewById<android.widget.Button>(R.id.btnLeftClick).setOnClickListener {
            service?.clickMouse("left", "click")
        }
        findViewById<android.widget.Button>(R.id.btnRightClick).setOnClickListener {
            service?.clickMouse("right", "click")
        }
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                pointerCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) * sensitivity
                val dy = (event.y - lastY) * sensitivity
                if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) {
                    if (pointerCount >= 2) {
                        // deux doigts = defilement (scroll)
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
                    // tap simple = clic gauche
                    service?.clickMouse("left", "click")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(connection)
    }
}
