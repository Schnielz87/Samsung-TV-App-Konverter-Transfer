package de.tvtransfer.net

import de.tvtransfer.tv.SamsungTv
import de.tvtransfer.tv.TvInfo
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class DeviceKind { SAMSUNG_TV, ANDROID_STICK }

data class FoundDevice(
    val host: String,
    val kind: DeviceKind,
    val name: String,
    /** Nur Samsung: Entwicklermodus-Port 26101 ist offen. */
    val sdbOpen: Boolean = false,
    val tvInfo: TvInfo? = null,
) {
    val title: String get() = when (kind) {
        DeviceKind.SAMSUNG_TV -> "$name ($host)"
        DeviceKind.ANDROID_STICK -> "Android-Stick ($host)"
    }
}

/** Sucht im eigenen WLAN nach Samsung-Fernsehern (Port 8001/26101) und Android-Sticks (Port 5555). */
object Discovery {

    /** Eigene IPv4-Adressen im Heimnetz (192.168.x.x, 10.x.x.x, 172.16-31.x.x). */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    /** Prüft genau eine Adresse (für manuelle Eingabe). */
    fun probe(host: String, timeoutMs: Int = 1500): List<FoundDevice> {
        val result = ArrayList<FoundDevice>()
        val tvApi = isOpen(host, 8001, timeoutMs)
        val sdb = isOpen(host, 26101, timeoutMs)
        if (tvApi || sdb) {
            val info = if (tvApi) SamsungTv.fetchInfo(host) else null
            result += FoundDevice(host, DeviceKind.SAMSUNG_TV, info?.name ?: "Samsung TV", sdb, info)
        }
        if (isOpen(host, 5555, timeoutMs)) result += FoundDevice(host, DeviceKind.ANDROID_STICK, "Android-Stick")
        return result
    }

    fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<FoundDevice> {
        val own = localAddresses()
        val hosts = own.flatMap { ip ->
            val base = ip.substringBeforeLast('.')
            (1..254).map { "$base.$it" }.filter { it != ip }
        }.distinct()
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(64)
        try {
            var done = 0
            val tasks = hosts.map { h -> Callable { probe(h, 400).also { synchronized(this) { done++; onProgress(done, hosts.size) } } } }
            return pool.invokeAll(tasks, 60, TimeUnit.SECONDS)
                .flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }
                .sortedWith(compareBy({ it.kind }, { it.host }))
        } finally {
            pool.shutdownNow()
        }
    }
}
