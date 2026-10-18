package dev.reversenet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import dev.reversenet.nat.Ipv4
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Holds both networks on purpose: Wi-Fi (the ESP32, no internet) for the control link and
 * cellular for the real traffic. Sockets are bound explicitly so Android's default routing
 * cannot send tunnel traffic the wrong way.
 */
class NetworkManager(context: Context, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var cellular: Network? = null
        private set
    @Volatile var wifi: Network? = null
        private set

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { cellular = network; onChange() }
        override fun onLost(network: Network) { if (cellular == network) cellular = null; onChange() }
    }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wifi = network; onChange() }
        override fun onLost(network: Network) { if (wifi == network) wifi = null; onChange() }
    }

    fun start() {
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cellularCallback,
        )
        // The ESP32 Wi-Fi has no internet; whether Android matches it depends on the OS version.
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            wifiCallback,
        )
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(cellularCallback) }
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        cellular = null
        wifi = null
    }

    fun newCellularUdpSocket(): DatagramSocket? = bound(cellular)
    /** Connected TCP socket to [ip]:[port] over the cellular network, or null if it cannot be made. */
    fun connectCellularTcp(ip: Int, port: Int): Socket? {
        val network = cellular ?: run {
            RnLog.w("no cellular network; cannot open TCP to ${Ipv4.toString(ip)}:$port")
            return null
        }
        val socket = Socket()
        return try {
            network.bindSocket(socket) // must happen before connect
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getByAddress(Ipv4.toBytes(ip)), port), 10_000)
            socket
        } catch (e: IOException) {
            RnLog.w("tcp connect failed: ${e.message}")
            socket.close()
            null
        }
    }

    fun newWifiUdpSocket(): DatagramSocket? = bound(wifi)

    private fun bound(network: Network?): DatagramSocket? {
        network ?: return null
        val socket = DatagramSocket()
        return try {
            network.bindSocket(socket)
            socket
        } catch (e: IOException) {
            RnLog.w("bindSocket failed: ${e.message}")
            socket.close()
            null
        }
    }
}
