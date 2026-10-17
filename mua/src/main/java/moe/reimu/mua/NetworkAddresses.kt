package moe.reimu.mua

import java.net.Inet4Address
import java.net.NetworkInterface

/** IPv4 addresses usable by clients of the all-interfaces listener. */
internal fun deviceNetworkAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        .sortedBy { !it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
}.getOrDefault(emptyList())
