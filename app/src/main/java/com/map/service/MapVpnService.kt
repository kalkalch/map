package com.map.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.map.sstp.SstpRoutePlanner
import com.map.util.LocalSettings
import com.map.util.SstpConnectionLog
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import timber.log.Timber

class MapVpnService : VpnService() {
    private var tunnelInterface: ParcelFileDescriptor? = null
    private var activeRoutes: List<SstpRoutePlanner.RouteEntry> = emptyList()
    private var activeTunnelAddress: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeInstance = this
        val action = intent?.getStringExtra(EXTRA_ACTION)
        when (action) {
            ACTION_PREPARE_ROUTE -> prepareRoutePlan()
            ACTION_STOP -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        if (activeInstance === this) {
            activeInstance = null
        }
    }

    private fun prepareRoutePlan() {
        val settings = LocalSettings(this)
        if (!settings.isSstpEnabled()) {
            Timber.tag(TAG).i("Skip VPN route prepare: SSTP disabled")
            return
        }

        val plan = try {
            SstpRoutePlanner.plan(settings)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to build VPN route plan")
            return
        }

        if (!plan.isValid) {
            Timber.tag(TAG).w("VPN route plan invalid: %s", plan.detail)
            return
        }

        val routeSummary = plan.routes.joinToString(", ") { "${it.routeAddress}/${it.routePrefix}" }
        SstpConnectionLog.log(
            TAG,
            "VPN route plan is ready and waiting for PPP address: routes=$routeSummary"
        )
    }

    @Synchronized
    fun establishTunnelForPppAddress(pppAssignedAddress: String) {
        val settings = LocalSettings(this)
        if (!settings.isSstpEnabled()) {
            throw IllegalStateException("SSTP is disabled")
        }
        if (pppAssignedAddress.isBlank()) {
            throw IllegalArgumentException("PPP assigned address is empty")
        }

        val plan = SstpRoutePlanner.plan(settings)
        if (!plan.isValid) {
            throw IllegalStateException("VPN route plan invalid: ${plan.detail}")
        }

        stopTunnel()

        val builder = Builder()
            .setSession("MAP-SSTP")
            .addAddress(pppAssignedAddress, TUN_PREFIX)
            .setBlocking(false)

        for (route in plan.routes) {
            builder.addRoute(route.routeAddress, route.routePrefix)
        }

        val configuredMtu = settings.getSstpTunnelMtu()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMtu(configuredMtu)
        }

        try {
            builder.addAllowedApplication(packageName)
            SstpConnectionLog.log(TAG, "VPN allowed application: $packageName")
        } catch (e: PackageManager.NameNotFoundException) {
            SstpConnectionLog.logError(TAG, "Failed to add MAP package to VPN allowed apps", e)
        }

        try {
            tunnelInterface = builder.establish()
            activeRoutes = plan.routes
            activeTunnelAddress = pppAssignedAddress
            SstpConnectionLog.log(
                TAG,
                "VPN interface established from PPP address: localAddress=$pppAssignedAddress/$TUN_PREFIX"
            )
            for (route in activeRoutes) {
                val targetHost = route.remoteProxy?.host ?: "n/a"
                val targetPort = route.remoteProxy?.port ?: -1
                Timber.tag(TAG).i("VPN route established for %s", route.routeAddress)
                SstpConnectionLog.log(
                    TAG,
                    "VPN route established: target=$targetHost:$targetPort route=${route.routeAddress}/${route.routePrefix}"
                )
                SstpConnectionLog.log(
                    TAG,
                    "VPN route dev=tun target=$targetHost:$targetPort route=${route.routeAddress}/${route.routePrefix} vpnInterface=$pppAssignedAddress/$TUN_PREFIX"
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to establish VPN route")
            SstpConnectionLog.logError(TAG, "Failed to establish VPN route", e)
        }
    }

    /**
     * Logs VPN TUN MTU after the SSTP session is fully up (Call Connected + relay).
     * The value is the iface MTU from the OS; timing matches when the tunnel is in use.
     */
    fun logTunnelMtuAfterSstpReady() {
        val addr = activeTunnelAddress
        if (addr.isNullOrBlank()) {
            return
        }
        logResolvedVpnMtu(addr)
    }

    /**
     * Reads TUN MTU for [pppAssignedAddress]. There is no single official API;
     * we try [LinkProperties] on API 29+, then match [NetworkInterface] by VPN address.
     * Never throws: diagnostics must not affect VPN setup.
     */
    private fun logResolvedVpnMtu(pppAssignedAddress: String) {
        try {
            val fromLink = readMtuFromLinkProperties()
            val fromIface = readMtuFromNetworkInterface(pppAssignedAddress)
            val mtu = fromLink ?: fromIface
            val source = when {
                fromLink != null && fromIface != null && fromLink == fromIface -> "link+iface"
                fromLink != null -> "link"
                fromIface != null -> "iface"
                else -> null
            }
            if (mtu != null && mtu > 0) {
                SstpConnectionLog.log(TAG, "VPN tunnel MTU=$mtu (source=$source)")
                Timber.tag(TAG).i("VPN tunnel MTU=%d (source=%s)", mtu, source)
            } else {
                SstpConnectionLog.log(TAG, "VPN tunnel MTU: unknown (could not resolve)")
                Timber.tag(TAG).i("VPN tunnel MTU: unknown (could not resolve)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "VPN tunnel MTU log skipped")
        }
    }

    private fun readMtuFromLinkProperties(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.activeNetwork ?: return null
            val mtu = cm.getLinkProperties(network)?.mtu ?: return null
            mtu.takeIf { it > 0 }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read MTU from LinkProperties")
            null
        }
    }

    private fun readMtuFromNetworkInterface(pppAssignedAddress: String): Int? {
        val target = pppAssignedAddress.trim()
        if (target.isEmpty()) {
            return null
        }
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (target == addr.hostAddress) {
                        return ni.mtu.takeIf { it > 0 }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read MTU from NetworkInterface")
            null
        }
    }

    private fun stopTunnel() {
        val routeSummaries = activeRoutes.map { route ->
            val targetHost = route.remoteProxy?.host ?: "n/a"
            val targetPort = route.remoteProxy?.port ?: -1
            "target=$targetHost:$targetPort route=${route.routeAddress}/${route.routePrefix}"
        }
        try {
            tunnelInterface?.close()
        } catch (ignored: Exception) {
        } finally {
            tunnelInterface = null
            activeRoutes = emptyList()
            activeTunnelAddress = null
        }
        if (routeSummaries.isNotEmpty()) {
            for (routeSummary in routeSummaries) {
                SstpConnectionLog.log(TAG, "VPN route removed with tunnel stop: $routeSummary")
            }
        } else {
            SstpConnectionLog.log(TAG, "VPN tunnel stopped with no active route")
        }
    }

    fun duplicateTunnelInterface(): ParcelFileDescriptor? {
        return try {
            tunnelInterface?.dup()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to dup TUN interface")
            null
        }
    }

    fun protectSocket(socket: Socket): Boolean {
        return protect(socket)
    }

    fun logActiveRouteState() {
        if (tunnelInterface == null || activeRoutes.isEmpty()) {
            SstpConnectionLog.log(TAG, "VPN route is not active")
            return
        }
        for (route in activeRoutes) {
            val targetHost = route.remoteProxy?.host ?: "n/a"
            val targetPort = route.remoteProxy?.port ?: -1
            SstpConnectionLog.log(
                TAG,
                "VPN route is active: target=$targetHost:$targetPort route=${route.routeAddress}/${route.routePrefix} vpnInterface=${activeTunnelAddress ?: "n/a"}/$TUN_PREFIX"
            )
        }
    }

    companion object {
        private const val TAG = "MapVpnService"
        private const val TUN_PREFIX = 32
        const val EXTRA_ACTION = "action"
        const val ACTION_PREPARE_ROUTE = "prepare_route"
        const val ACTION_STOP = "stop"

        @Volatile
        var activeInstance: MapVpnService? = null
            private set
    }
}
