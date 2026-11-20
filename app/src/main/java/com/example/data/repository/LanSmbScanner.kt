package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.domain.model.DiscoveredSmbHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers SMB/NAS servers on the local Wi-Fi/Ethernet subnet using:
 * 1) Fast priority check of active Wi-Fi Gateway / gpon.net router USB shares
 * 2) Android NsdManager mDNS (_smb._tcp.) discovery
 * 3) Parallel /24 IPv4 TCP port 445 probe across active local interfaces
 */
class LanSmbScanner(private val context: Context) {

    suspend fun scanLanHosts(
        onIncrementalResult: (List<DiscoveredSmbHost>) -> Unit = {}
    ): List<DiscoveredSmbHost> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredSmbHost>()
        val gatewayIps = collectGatewayIpv4Addresses()
        val nsdManager = runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
        var discoveryListener: NsdManager.DiscoveryListener? = null

        fun publishSorted(): List<DiscoveredSmbHost> {
            val list = found.values.sortedWith(
                compareByDescending<DiscoveredSmbHost> { it.host in gatewayIps || it.displayName.startsWith("Router") }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.host }
            )
            onIncrementalResult(list)
            return list
        }

        fun recordHost(hostIp: String, preferredName: String?, port: Int, source: String) {
            if (hostIp.isBlank()) return
            val existing = found[hostIp]
            val cleanName = preferredName?.trim()?.takeIf { it.isNotBlank() && it != hostIp }
            val friendlyName = when {
                cleanName != null -> cleanName
                hostIp in gatewayIps -> "Router Wi-Fi ($hostIp)"
                else -> existing?.displayName ?: "Equipo ($hostIp)"
            }
            val next = DiscoveredSmbHost(
                host = hostIp,
                displayName = friendlyName,
                port = port,
                source = if (existing != null && existing.source.contains("mDNS")) existing.source else source
            )
            found[hostIp] = next
            publishSorted()
        }

        // Start mDNS _smb._tcp. discovery in parallel
        if (nsdManager != null) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val resolvedHost = if (Build.VERSION.SDK_INT >= 34) {
                                info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                                    ?: info.hostAddresses.firstOrNull()?.hostAddress
                            } else {
                                info.host?.hostAddress
                            }
                            if (!resolvedHost.isNullOrBlank()) {
                                recordHost(
                                    hostIp = resolvedHost,
                                    preferredName = info.serviceName,
                                    port = info.port.takeIf { it in 1..65535 } ?: 445,
                                    source = "Wi-Fi Auto"
                                )
                            }
                        }
                    }
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener
            runCatching {
                nsdManager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }

        try {
            // 1) Fast immediate check for GPON / Router Gateway & common router hostnames
            val priorityHosts = linkedSetOf<Pair<String, String?>>()
            gatewayIps.forEach { gw -> priorityHosts.add(gw to "Router Wi-Fi ($gw)") }
            listOf("gpon.net", "router.local", "myrouter.local").forEach { hostname ->
                val resolvedIp = runCatching {
                    InetAddress.getAllByName(hostname)
                        .firstOrNull { it is Inet4Address }
                        ?.hostAddress
                }.getOrNull()
                if (!resolvedIp.isNullOrBlank()) {
                    priorityHosts.add(resolvedIp to "Router USB ($hostname)")
                }
            }

            coroutineScope {
                priorityHosts.map { (ip, label) ->
                    async {
                        if (probeTcpPort(ip, 445, timeoutMs = 350)) {
                            recordHost(
                                hostIp = ip,
                                preferredName = label,
                                port = 445,
                                source = "Router Wi-Fi"
                            )
                        }
                    }
                }.awaitAll()
            }

            // 2) Parallel /24 IPv4 subnet scan for NAS / PCs
            val localIpv4s = collectLocalIpv4Addresses()
            val candidateIps = buildSubnetCandidates(localIpv4s)
                .filterNot { ip -> found.containsKey(ip) }
            val semaphore = Semaphore(48)

            coroutineScope {
                candidateIps.map { ip ->
                    async {
                        semaphore.withPermit {
                            if (probeTcpPort(ip, 445, timeoutMs = 220)) {
                                val resolvedName = runCatching {
                                    InetAddress.getByName(ip).hostName
                                        ?.substringBefore(".local")
                                        ?.takeIf { it != ip && it.isNotBlank() }
                                }.getOrNull()
                                recordHost(
                                    hostIp = ip,
                                    preferredName = resolvedName,
                                    port = 445,
                                    source = "Red Wi-Fi"
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            if (nsdManager != null && found.isEmpty()) {
                delay(450L)
            }
        } finally {
            discoveryListener?.let { listener ->
                runCatching { nsdManager?.stopServiceDiscovery(listener) }
            }
        }

        publishSorted()
    }

    private fun probeTcpPort(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun collectGatewayIpv4Addresses(): Set<String> {
        val gateways = linkedSetOf<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return@runCatching
            val linkProps: LinkProperties? = cm.getLinkProperties(network)
            linkProps?.routes?.forEach { route ->
                val gw = route.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                    gw.hostAddress?.let { gateways.add(it) }
                }
            }
            linkProps?.dnsServers?.forEach { dns ->
                if (dns is Inet4Address && dns.isSiteLocalAddress) {
                    dns.hostAddress?.let { gateways.add(it) }
                }
            }
        }
        return gateways
    }

    private fun collectLocalIpv4Addresses(): List<String> {
        val result = linkedSetOf<String>()

        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps: LinkProperties? = if (network != null) cm.getLinkProperties(network) else null
            linkProps?.linkAddresses?.forEach { linkAddr ->
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    addr.hostAddress?.let { result.add(it) }
                }
            }
        }

        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
        }

        return result.toList()
    }

    private fun buildSubnetCandidates(localIps: List<String>): List<String> {
        val candidates = linkedSetOf<String>()
        val ownSet = localIps.toSet()
        for (ip in localIps.take(2)) {
            val parts = ip.split('.')
            if (parts.size != 4) continue
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            // Prioritize common router/NAS host numbers first
            for (priorityHost in listOf(1, 254, 2, 10, 50, 100)) {
                val target = "$prefix.$priorityHost"
                if (target !in ownSet) candidates.add(target)
            }
            for (hostPart in 1..254) {
                val target = "$prefix.$hostPart"
                if (target !in ownSet) {
                    candidates.add(target)
                }
            }
        }
        return candidates.toList()
    }
}
