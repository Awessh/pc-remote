package com.pcremote.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class RunningWindowsActivity : AppCompatActivity(), ConnectionListener {

    private var service: RemoteConnectionService? = null
    private var bound = false
    private lateinit var recycler: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: WindowsAdapter

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            service?.addListener(this@RunningWindowsActivity)
            bound = true
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_windows)

        recycler = findViewById(R.id.recyclerWindows)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvEmpty = findViewById(R.id.tvEmptyWindows)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = WindowsAdapter(
            emptyList(),
            onClose = { w -> service?.closeWindow(w.hwnd); Toast.makeText(this, "Fermeture de ${w.title}...", Toast.LENGTH_SHORT).show() },
            onForceClose = { w -> confirmForceClose(w) }
        )
        recycler.adapter = adapter

        swipeRefresh.setOnRefreshListener { refresh() }

        bindService(Intent(this, RemoteConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun confirmForceClose(w: WindowInfo) {
        AlertDialog.Builder(this)
            .setTitle("Forcer la fermeture ?")
            .setMessage("\"${w.title}\" sera arrete immediatement, sans sauvegarde.")
            .setPositiveButton("Forcer") { _, _ -> service?.forceClose(w.pid) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun refresh() {
        service?.requestWindows()
    }

    override fun onWindowsList(windows: List<WindowInfo>) {
        runOnUiThread {
            swipeRefresh.isRefreshing = false
            adapter.update(windows)
            tvEmpty.visibility = if (windows.isEmpty()) View.VISIBLE else View.GONE
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

class WindowsAdapter(
    private var items: List<WindowInfo>,
    private val onClose: (WindowInfo) -> Unit,
    private val onForceClose: (WindowInfo) -> Unit
) : RecyclerView.Adapter<WindowsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvWindowTitle)
        val btnClose: View = view.findViewById(R.id.btnCloseWindow)
        val btnForce: View = view.findViewById(R.id.btnForceCloseWindow)
    }

    fun update(newItems: List<WindowInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_window, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val w = items[position]
        holder.title.text = w.title
        holder.btnClose.setOnClickListener { onClose(w) }
        holder.btnForce.setOnClickListener { onForceClose(w) }
    }

    override fun getItemCount() = items.size
}
