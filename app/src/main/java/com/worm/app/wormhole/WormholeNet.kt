package com.worm.app.wormhole

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.coroutines.resume

/** What the QR code carries: where to reach the host, how to prove it's you, and (maybe) the hotspot. */
data class WormholeInvite(
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val name: String,
    val ssid: String? = null,
    val pass: String? = null,
    val wpa3: Boolean = false,
) {
    fun toUri(): String = Uri.Builder().scheme("wormhole").authority("join")
        .appendQueryParameter("h", hosts.joinToString(","))
        .appendQueryParameter("p", port.toString())
        .appendQueryParameter("t", token)
        .appendQueryParameter("n", name)
        .apply {
            if (ssid != null) {
                appendQueryParameter("s", ssid)
                appendQueryParameter("k", pass.orEmpty())
                if (wpa3) appendQueryParameter("x", "3")
            }
        }.build().toString()

    companion object {
        fun parse(text: String): WormholeInvite? = runCatching {
            val u = Uri.parse(text)
            if (u.scheme != "wormhole") return null
            WormholeInvite(
                hosts = u.getQueryParameter("h")!!.split(',').filter { it.isNotBlank() },
                port = u.getQueryParameter("p")!!.toInt(),
                token = u.getQueryParameter("t")!!,
                name = u.getQueryParameter("n") ?: "Worm",
                ssid = u.getQueryParameter("s"),
                pass = u.getQueryParameter("k"),
                wpa3 = u.getQueryParameter("x") == "3",
            )
        }.getOrNull()
    }
}

data class HotspotInfo(val ssid: String, val pass: String, val wpa3: Boolean)

object WormholeNet {
    /** Every private IPv4 address this phone has (Wi-Fi, hotspot…), Wi-Fi-looking ones first. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni.name to it.hostAddress!! } }
            .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }
            .map { it.second }
            .distinct()
    }.getOrDefault(emptyList())

    fun freePort(preferred: Int = 8787): Int =
        runCatching { ServerSocket(preferred).use { it.localPort } }.getOrElse { ServerSocket(0).use { it.localPort } }

    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    // ---------------------------------------------------------------- hotspot

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Starts Android's private "local-only" hotspot (no internet sharing, random name + password). */
    @SuppressLint("MissingPermission")
    suspend fun startHotspot(context: Context): HotspotInfo = suspendCancellableCoroutine { cont ->
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                val info = if (Build.VERSION.SDK_INT >= 30) {
                    val c = res.softApConfiguration
                    val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") c.ssid
                    HotspotInfo(ssid.orEmpty(), c.passphrase.orEmpty(), c.securityType == android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)
                } else {
                    @Suppress("DEPRECATION") val c = res.wifiConfiguration
                    HotspotInfo(c?.SSID.orEmpty(), c?.preSharedKey.orEmpty(), false)
                }
                if (cont.isActive) cont.resume(info)
            }
            override fun onFailed(reason: Int) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(
                    when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "This phone doesn't allow hotspots"
                        ERROR_INCOMPATIBLE_MODE -> "Turn off your normal hotspot first"
                        else -> "Couldn't start a hotspot (error $reason)"
                    },
                )))
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun stopHotspot() {
        runCatching { reservation?.close() }
        reservation = null
    }

    // ------------------------------------------------------- joining hotspots

    private var joinCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Joins the host's hotspot (Android 10+ shows a one-tap "Connect" prompt) and routes Worm's
     * traffic through it. Returns null on older phones: they join from Wi-Fi settings instead.
     */
    suspend fun joinHotspot(context: Context, ssid: String, pass: String, wpa3: Boolean): Network? {
        if (Build.VERSION.SDK_INT < 29) return null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (wpa3) setWpa3Passphrase(pass) else setWpa2Passphrase(pass)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                joinCallback = cb
                cm.requestNetwork(req, cb)
            }
        }
    }

    fun leaveHotspot(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.bindProcessToNetwork(null)
        joinCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        joinCallback = null
    }

    // ------------------------------------------------------------- discovery

    const val SERVICE = "_wormhole._tcp."
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** Advertises us on the local Wi-Fi so nearby Worm devices can find us without a QR code. */
    fun advertise(context: Context, name: String, port: Int) {
        val nsd = context.getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = SERVICE; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stopAdvertising(context: Context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    /** Finds Worm devices on this Wi-Fi. [onFound] gets (name, host, port). */
    @Suppress("DEPRECATION")
    fun discover(context: Context, onFound: (String, String, Int) -> Unit) {
        val nsd = context.getSystemService(NsdManager::class.java)
        stopDiscovery(context)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            val host = r.host?.hostAddress ?: return
                            if (host in localAddresses()) return
                            onFound(r.serviceName, host, r.port)
                        }
                    })
                }
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stopDiscovery(context: Context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }
}
