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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppsListActivity : AppCompatActivity(), ConnectionListener {

    private var service: RemoteConnectionService? = null
    private var bound = false
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppsAdapter
    private lateinit var etFilter: android.widget.EditText
    private lateinit var tvEmpty: TextView

    private var allApps: List<AppInfo> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RemoteConnectionService.LocalBinder).getService()
            service?.addListener(this@AppsListActivity)
            bound = true
            service?.requestApps()
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        recycler = findViewById(R.id.recyclerApps)
        etFilter = findViewById(R.id.etFilter)
        tvEmpty = findViewById(R.id.tvEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppsAdapter(emptyList()) { app ->
            service?.launchApp(app.path)
            Toast.makeText(this, "Lancement de ${app.name}...", Toast.LENGTH_SHORT).show()
        }
        recycler.adapter = adapter

        etFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        bindService(Intent(this, RemoteConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) allApps
        else allApps.filter { it.name.contains(query, ignoreCase = true) }
        adapter.update(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onAppsList(apps: List<AppInfo>) {
        runOnUiThread {
            allApps = apps
            applyFilter(etFilter.text.toString())
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

class AppsAdapter(
    private var items: List<AppInfo>,
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvAppName)
        val avatar: TextView = view.findViewById(R.id.tvAppAvatar)
    }

    fun update(newItems: List<AppInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.name.text = app.name
        holder.avatar.text = app.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = items.size
}
