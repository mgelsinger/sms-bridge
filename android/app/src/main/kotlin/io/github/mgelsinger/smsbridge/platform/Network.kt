package io.github.mgelsinger.smsbridge.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress

/** Reports the Wi-Fi IPv4 address, or null when Wi-Fi is down. The server binds only to it. */
class WifiMonitor(context: Context, private val onChange: (InetAddress?) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var network: Network? = null
    private var address: InetAddress? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = update(n, lp)
        override fun onLost(n: Network) {
            synchronized(this@WifiMonitor) {
                if (n != network) return
                network = null
                emit(null)
            }
        }
    }

    fun start() {
        emit(null)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    @Synchronized
    private fun update(n: Network, lp: LinkProperties) {
        val ipv4 = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        network = n
        emit(ipv4)
    }

    private fun emit(a: InetAddress?) {
        if (a == address && a != null) return
        address = a
        onChange(a)
    }
}

/** Advertises `_smsbridge._tcp` so the PC finds the phone without typing an address. */
class NsdAdvertiser(context: Context, private val log: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    fun register(name: String, port: Int, fingerprint: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            setAttribute("fp", fingerprint.take(16))
        }
        lock = wifi.createMulticastLock("sms-bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = log("advertising as ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = log("mDNS registration failed ($code)")
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
        lock?.let { runCatching { it.release() } }
        lock = null
    }

    companion object {
        const val SERVICE_TYPE = "_smsbridge._tcp"
    }
}
