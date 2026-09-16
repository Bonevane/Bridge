package com.bonevane.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The only door into adbd we allow ourselves: *wireless debugging*, for about a
 * second, on the phone's own loopback.
 *
 *   1. turn it on (adb_wifi_enabled, needs Wi-Fi; Android picks a random port)
 *   2. find the port with mDNS (`_adb-tls-connect._tcp`, advertised on this phone)
 *   3. connect over TLS with our already-authorized key, run the commands
 *   4. turn wireless debugging off again
 *
 * We deliberately never use `adb tcpip`: that leaves adbd listening on the
 * Wi-Fi for the whole session, and anyone on the network could make the
 * "Allow USB debugging?" dialog pop up. Wireless debugging is on only while
 * [withAdb] runs.
 *
 * No pairing code needed: adbd accepts a TLS client whose key it already trusts,
 * and ours was accepted the first time ("Always allow").
 */
object WifiBootstrap {
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp"

    fun <T> withAdb(ctx: Context, block: (AdbClient) -> T): T {
        // Android only offers wireless debugging on Wi-Fi, so say so up front
        // rather than after a 25 s search that can't succeed. The message names
        // the two real ways forward; the Mac shows it verbatim.
        if (!wifiConnected(ctx)) {
            error("the phone's helper needs restarting, which needs Wi-Fi or a cable. " +
                  "Either turn Wi-Fi on for a moment, or plug the phone in and use Set Up Over USB.")
        }
        Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
        try {
            val port = discoverPort(ctx, timeoutSec = 25)
                ?: error("wireless debugging port not found (is Wi-Fi on?)")
            TunnelState.log("Wireless debugging on port $port")
            return AdbClient("127.0.0.1", port, AdbKey.load(ctx)).use {
                it.connect()
                block(it)
            }
        } finally {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 0)
        }
    }

    private fun localAddresses(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .map { it.hostAddress?.substringBefore('%') ?: "" }
            .toSet()
    }.getOrDefault(emptySet())

    private fun wifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** Blocks until the phone's own wireless-debugging service shows up on mDNS. */
    private fun discoverPort(ctx: Context, timeoutSec: Long): Int? {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val done = CountDownLatch(1)
        var port: Int? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        // Only this phone's own adbd counts: a rogue advert on the
                        // Wi-Fi could otherwise steer the port (we connect to
                        // loopback regardless, so it could only make us fail,
                        // but there's no reason to let it).
                        val local = localAddresses()
                        val host = resolved.host?.hostAddress
                        if (host != null && host !in local) {
                            TunnelState.log("Ignored a wireless-debugging advert from $host")
                            return
                        }
                        port = resolved.port; done.countDown()
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        TunnelState.log("mDNS resolve failed: $code")
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { TunnelState.log("mDNS start failed: $code"); done.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        done.await(timeoutSec, TimeUnit.SECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return port
    }
}
