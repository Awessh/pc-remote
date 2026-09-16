package com.pcremote.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class DiscoveredDevice(val ip: String, val port: Int, val name: String)

/**
 * Envoie un broadcast UDP sur le reseau local ; tout serveur PCRemote qui
 * l'entend repond avec son IP et son port TCP.
 */
object DeviceDiscovery {
    private const val UDP_PORT = 58433

    suspend fun discover(timeoutMs: Int = 3000): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredDevice>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            val msg = "PCREMOTE_DISCOVER".toByteArray()
            val packet = DatagramPacket(msg, msg.size, InetSocketAddress("255.255.255.255", UDP_PORT))
            socket.send(packet)

            val buf = ByteArray(1024)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val json = JSONObject(String(resp.data, 0, resp.length))
                    val ip = resp.address.hostAddress ?: continue
                    val port = json.optInt("port", 58432)
                    val name = json.optString("name", ip)
                    if (results.none { it.ip == ip }) {
                        results.add(DiscoveredDevice(ip, port, name))
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            // pas de reseau disponible ou erreur socket - on renvoie la liste (peut-etre vide)
        } finally {
            socket?.close()
        }
        results
    }
}
