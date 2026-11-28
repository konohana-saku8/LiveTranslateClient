package com.arkueid.livetranslateclient

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Locates the LiveTranslate PC on the local network without the user typing
 * an address.
 *
 * The PC answers one UDP broadcast on [DISCOVERY_PORT] with its websocket
 * port, so custom ports and changing DHCP leases need no manual input. Older
 * PC builds have no responder, so we fall back to probing every host in the
 * phone's subnet on the last-known TCP port.
 *
 * Every method here blocks; call [discover] from a worker thread.
 */
object LanDiscovery {
    const val DISCOVERY_PORT = 8766

    private const val MAGIC = "LIVETRANSLATE_DISCOVER_V1"
    private const val TAG = "LanDiscovery"

    private const val UDP_TIMEOUT_MS = 1500L
    private const val SCAN_THREADS = 64
    private const val TCP_TIMEOUT_MS = 250

    data class Device(val host: String, val port: Int, val tokenRequired: Boolean)

    /** Presents the server found via broadcast, or null when none answers. */
    fun discover(tcpPort: Int, cancelled: () -> Boolean = { false }): Device? {
        broadcastProbe(cancelled)?.let { return it }
        if (cancelled()) return null
        return subnetScan(tcpPort, cancelled)
    }

    // ── UDP broadcast ──

    private fun broadcastProbe(cancelled: () -> Boolean): Device? {
        val payload = MAGIC.toByteArray(Charsets.UTF_8)
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 150
            }
        } catch (e: IOException) {
            Log.w(TAG, "UDP probe socket failed", e)
            return null
        }
        try {
            for (address in broadcastTargets()) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
                } catch (e: IOException) {
                    Log.d(TAG, "broadcast to $address failed: ${e.message}")
                }
            }

            val deadline = System.currentTimeMillis() + UDP_TIMEOUT_MS
            val buffer = ByteArray(1024)
            while (System.currentTimeMillis() < deadline && !cancelled()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    break
                }
                parseReply(packet)?.let { return it }
            }
        } finally {
            socket.close()
        }
        return null
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: IOException) {
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.toList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.interfaceAddresses) {
                    val broadcast = address.broadcast ?: continue
                    if (address.address is Inet4Address) targets.add(broadcast)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "interface enumeration failed", e)
        }
        return targets.toList()
    }

    private fun parseReply(packet: DatagramPacket): Device? {
        return try {
            val json = JSONObject(
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            )
            if (json.optString("app") != "livetranslate") return null
            if (json.optInt("protocol", 0) != 1) return null
            val port = json.optInt("port", 0)
            val host = (packet.address as? Inet4Address)?.hostAddress
            if (port !in 1..65535 || host == null) {
                null
            } else {
                Device(host, port, json.optBoolean("token_required", false))
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── TCP subnet scan (older PC builds) ──

    private fun subnetScan(port: Int, cancelled: () -> Boolean): Device? {
        val targets = scanTargets()
        if (targets.isEmpty()) return null

        val pool = Executors.newFixedThreadPool(SCAN_THREADS) { runnable ->
            Thread(runnable, "lan-scan").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<Device?>(pool)
        val stop = AtomicBoolean(false)
        try {
            for (address in targets) {
                completion.submit(Callable {
                    if (stop.get() || cancelled()) null else probeTcp(address, port)
                })
            }
            repeat(targets.size) {
                if (cancelled()) return null
                val future = completion.poll(
                    TCP_TIMEOUT_MS.toLong() + 100, TimeUnit.MILLISECONDS
                ) ?: return@repeat
                val device = try {
                    future.get()
                } catch (_: Exception) {
                    null
                }
                if (device != null) {
                    stop.set(true)
                    return device
                }
            }
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
        return null
    }

    private fun probeTcp(address: InetAddress, port: Int): Device? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                Device(address.hostAddress ?: return null, port, false)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun scanTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.interfaceAddresses) {
                    val ip = address.address
                    if (ip is Inet4Address && ip.isSiteLocalAddress) {
                        addSubnet(targets, ip, address.networkPrefixLength.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "scan target enumeration failed", e)
        }
        return targets.toList()
    }

    /**
     * Adds the hosts of the local subnet. Huge prefixes (/16 VPNs) are capped
     * at /23 so a fallback scan still finishes in a few seconds.
     */
    private fun addSubnet(out: MutableSet<InetAddress>, local: Inet4Address, prefixLength: Int) {
        val prefix = prefixLength.coerceIn(23, 30)
        val localValue = ipToLong(local)
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = localValue and mask
        val size = 1 shl (32 - prefix)
        for (offset in 1 until size - 1) {
            val value = network + offset
            if (value == localValue) continue
            out.add(longToIp(value))
        }
    }

    private fun ipToLong(address: InetAddress): Long {
        var value = 0L
        for (byte in address.address) {
            value = (value shl 8) or (byte.toLong() and 0xFF)
        }
        return value
    }

    private fun longToIp(value: Long): InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    )
}
