package com.adbhost.app.engine

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun localIpv4s(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    fun localIpv4Text(): String {
        val addresses = localIpv4s()
        return when {
            addresses.isEmpty() -> LOOPBACK
            else -> addresses.joinToString("  /  ")
        }
    }

    fun localIpv4(): String = localIpv4s().firstOrNull { it.contains(".") }
        ?: localIpv4Text()

    private const val LOOPBACK = "127.0.0.1"
}
