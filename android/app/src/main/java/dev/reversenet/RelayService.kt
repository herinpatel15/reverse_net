package dev.reversenet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.reversenet.nat.PacketDispatcher
import dev.reversenet.nat.TcpNat
import dev.reversenet.nat.UdpNat
import java.net.InetSocketAddress

/** Foreground service: joins the ESP32 link and relays its IP packets out over cellular. */
class RelayService : Service() {
    private lateinit var nm: NetworkManager
    private lateinit var udp: UdpNat
    private lateinit var tcp: TcpNat
    private lateinit var dispatcher: PacketDispatcher
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var link: LinkLoop? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RnLog.sink = { level, msg ->
            when (level) {
                'W' -> Log.w(RnLog.TAG, msg)
                'I' -> Log.i(RnLog.TAG, msg)
                else -> Log.d(RnLog.TAG, msg)
            }
        }
        nm = NetworkManager(this) { refreshStatus() }
        val sendIp = { packet: ByteArray -> link?.sendIp(packet); Unit }
        udp = UdpNat(nm::newCellularUdpSocket, sendIp)
        tcp = TcpNat(nm::connectCellularTcp, sendIp)
        dispatcher = PacketDispatcher(udp, tcp, sendIp) { RelayState.debugDump }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(buildNotification("Starting..."))
        if (!running) {
            running = true
            RelayState.running = true
            nm.start()
            worker = Thread(::relayLoop, "relay").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        RelayState.running = false
        link?.stop()
        nm.stop()
        udp.closeAll()
        tcp.closeAll()
        worker?.interrupt()
        RelayState.update("Stopped")
        super.onDestroy()
    }

    private fun relayLoop() {
        val esp = InetSocketAddress(Protocol.ESP_ADDRESS, Protocol.ESP_PORT)
        while (running) {
            val socket = nm.newWifiUdpSocket()
            if (socket == null) {
                refreshStatus()
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return }
                continue
            }
            val loop = LinkLoop(
                socket, esp,
                onIp = dispatcher::handleIp,
                onState = { up -> if (!up) { udp.closeAll(); tcp.closeAll() }; refreshStatus() },
                onTick = { udp.expire(); tcp.tick() },
            )
            link = loop
            refreshStatus()
            try {
                loop.run()
            } finally {
                link = null
                socket.close()
            }
            // run() returned because of a socket failure; retry shortly.
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return }
        }
    }

    private fun refreshStatus() {
        val text = "Wi-Fi: ${yn(nm.wifi != null)} | Cellular: ${yn(nm.cellular != null)} | " +
            "Link: ${if (link?.up == true) "up" else "down"}"
        RelayState.update(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun yn(b: Boolean) = if (b) "yes" else "NO"

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Relay status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("reverse-net relay")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP = "dev.reversenet.STOP"
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1
    }
}
