package io.github.mgelsinger.smsbridge.platform

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.mgelsinger.smsbridge.server.BridgeServer
import java.net.BindException
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the bridge available while Wi-Fi is up. Foreground service type
 * connectedDevice: the PC is a device this phone serves, and unlike dataSync it
 * has no daily time limit on Android 15.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var watcher: MessageWatcher
    private lateinit var wifi: WifiMonitor
    private lateinit var nsd: NsdAdvertiser
    private var server: BridgeServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifications.SERVICE_ID,
            Notifications.service(this, "Waiting for Wi-Fi"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val g = graph
        watcher = MessageWatcher(this, g.repo, g.hub, g.log::add, scope).also { it.start() }
        nsd = NsdAdvertiser(this, g.log::add)
        wifi = WifiMonitor(this) { address -> scope.launch { onWifi(address) } }.also { it.start() }
        g.log.add("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @Synchronized
    private fun onWifi(address: InetAddress?) {
        val g = graph
        stopServer()
        if (address == null) {
            g.setState(ServiceState.WaitingForWifi)
            Notifications.updateService(this, "Waiting for Wi-Fi")
            return
        }
        try {
            val identity = g.identity
            var s = BridgeServer(g.api, g.hub, identity, address, PORT, g.log::add)
            val port = try {
                s.start()
            } catch (e: BindException) {
                g.log.add("port $PORT busy, using a random port")
                s = BridgeServer(g.api, g.hub, identity, address, 0, g.log::add)
                s.start()
            }
            server = s
            nsd.register(g.repo.deviceInfo().deviceName, port, identity.fingerprint)
            g.setState(ServiceState.Running(address.hostAddress.orEmpty(), port))
            Notifications.updateService(this, "Available at ${address.hostAddress}:$port")
        } catch (e: Exception) {
            g.log.add("server failed to start: ${e.javaClass.simpleName}")
            g.setState(ServiceState.Failed(e.javaClass.simpleName))
            stopServer()
        }
    }

    private fun stopServer() {
        nsd.unregister()
        server?.stop()
        server = null
    }

    override fun onDestroy() {
        wifi.stop()
        watcher.stop()
        synchronized(this) { stopServer() }
        scope.cancel()
        graph.setState(ServiceState.Stopped)
        graph.log.add("service stopped")
        super.onDestroy()
    }

    companion object {
        const val PORT = 8765

        fun start(context: Context) {
            context.graph.settings.enabled = true
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.graph.settings.enabled = false
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}

/** Restarts the bridge after a reboot or an app update, if the user left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (ok && context.graph.settings.enabled) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }
}
