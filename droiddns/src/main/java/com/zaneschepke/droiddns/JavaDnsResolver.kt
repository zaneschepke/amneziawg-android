package com.zaneschepke.droiddns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import org.xbill.DNS.*
import java.net.*
import java.util.concurrent.atomic.AtomicReference

/**
 * A simple JavaDns Resolver implementation for Android.
 *
 * @param context An Android {@link Context}
 * @param preferredDnsServer Optional hostname or IP of the preferred DNS server to use for the resolution, default will be system configured DNS.
 * @param fallbackDnsServers Optional IP of the fallback DNS server to use, should there be issues with the preferred. Defaults to Cloudflare ipv4 and ipv6 respectively if not set.
 * @param timeoutSeconds Optional, defaults to 5.
 * @param maxRetries Optional, defaults to 2.
 */
class JavaDnsResolver @JvmOverloads constructor(
    private val context: Context,
    private val preferredDnsServer: String? = null,
    private val fallbackDnsServers: List<String> = listOf(DEFAULT_FALLBACK_DNS_IPV4, DEFAULT_FALLBACK_DNS_IPV6),
    private val timeoutSeconds: Int = 5,
    private val maxRetries: Int = 2
) : DnsResolver {

    companion object {
        const val DEFAULT_FALLBACK_DNS_IPV4 = "1.1.1.1"
        const val DEFAULT_FALLBACK_DNS_IPV6 = "2606:4700:4700::1111"
        private val TAG = JavaDnsResolver::class.java.simpleName

        private val IPV6_REGEX = Regex(
            "^(?:" +
                    "\\[(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}]|" + // [full:ipv6]
                    "\\[::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}]|" + // [::abbrev]
                    "\\[[0-9a-fA-F]{1,4}::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}]|" + // [abbrev::]
                    "\\[(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}]|" + // [partial:ipv6]
                    "\\[(?:[0-9a-fA-F]{1,4}:){1,7}:]|" + // [ipv6:]
                    "\\[::]|" + // [::]
                    "(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" + // full:ipv6
                    "::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}|" + // ::abbrev
                    "[0-9a-fA-F]{1,4}::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}|" + // abbrev::
                    "(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" + // partial:ipv6
                    "(?:[0-9a-fA-F]{1,4}:){1,7}:|" + // ipv6:
                    "::" + // ::
                    ")$"
        )
        private val IPV4_REGEX = Regex(
            "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                    "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )
    }

    private val dnsCache = AtomicReference(Cache())
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        require(timeoutSeconds > 0) { "Timeout must be positive" }
        require(maxRetries >= 0) { "Max retries must be non-negative" }
    }

    @Throws(UnknownHostException::class)
    @Synchronized
    override fun resolveDns(hostname: String, preferIpv4: Boolean, useCache: Boolean): List<String> {
        if (IPV4_REGEX.matches(hostname) || IPV6_REGEX.matches(hostname)) return listOf(hostname)

        val dnsServers = listOfNotNull(
            preferredDnsServer?.let { InetAddress.getByName(it) }?.hostAddress,
            getDeviceDefaultDnsServer()?.hostAddress
        ) + fallbackDnsServers

        var result: List<InetAddress> = emptyList()
        for (dnsServer in dnsServers) {
            Log.i(TAG, "Resolving $hostname with server: $dnsServer (preferIpv4=$preferIpv4)")
            result = tryResolve(hostname, dnsServer, preferIpv4, useCache)
            if (result.isNotEmpty()) break
        }
        return result.mapNotNull { it.hostAddress }
    }

    private fun tryResolve(hostname: String, dnsServer: String, preferIpv4: Boolean, useCache: Boolean): List<InetAddress> {
        return if (preferIpv4) {
            retryResolve(hostname, Type.A, dnsServer, useCache).ifEmpty {
                retryResolve(hostname, Type.AAAA, dnsServer, useCache)
            }
        } else {
            retryResolve(hostname, Type.AAAA, dnsServer, useCache).ifEmpty {
                retryResolve(hostname, Type.A, dnsServer, useCache)
            }
        }
    }

    private fun isIPv4Capable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (linkProperties.dnsServers.any { it is Inet4Address } ||
                        linkProperties.linkAddresses.any { it.address is Inet4Address })
    }

    private fun isIPv6Capable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (linkProperties.dnsServers.any { it is Inet6Address } ||
                        linkProperties.linkAddresses.any { it.address is Inet6Address })
    }

    private fun getDeviceDefaultDnsServer(): InetAddress? {
        val dnsServers = getSystemDnsServers()
        val hasIPv4 = isIPv4Capable()
        val hasIPv6 = isIPv6Capable()

        return when {
            !hasIPv4 && hasIPv6 -> dnsServers.firstOrNull { it is Inet6Address && isServerReachable(it) }
                ?: InetAddress.getByName(DEFAULT_FALLBACK_DNS_IPV6)
            hasIPv6 -> dnsServers.firstOrNull { it is Inet6Address && isServerReachable(it) }
                ?: dnsServers.firstOrNull { it is Inet4Address && isServerReachable(it) }
            hasIPv4 -> dnsServers.firstOrNull { it is Inet4Address && isServerReachable(it) }
            else -> null
        }
    }

    private fun getSystemDnsServers(): List<InetAddress> {
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return emptyList()

        val dnsServers = mutableListOf<InetAddress>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && linkProperties.isPrivateDnsActive) {
            linkProperties.privateDnsServerName?.let { privateDns ->
                try {
                    val resolved = tryResolve(privateDns, DEFAULT_FALLBACK_DNS_IPV4, preferIpv4 = true, useCache = false)
                    dnsServers.addAll(resolved.filter { isServerReachable(it) })
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve private DNS $privateDns: ${e.message}")
                }
            }
        }

        dnsServers.addAll(linkProperties.dnsServers.filter { isServerReachable(it) })
        return dnsServers
    }

    private fun isServerReachable(address: InetAddress): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, 53), timeoutSeconds * 1000 / 2)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Server ${address.hostAddress} not reachable: ${e.message}")
            false
        }
    }

    private fun retryResolve(hostname: String, type: Int, dnsServer: String, useCache: Boolean): List<InetAddress> {
        var lastException: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return resolve(hostname, type, dnsServer, useCache)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep((1 shl attempt) * 1000L)
                }
            }
        }
        Log.w(TAG, "Failed to resolve $hostname after $maxRetries retries: ${lastException?.message}")
        return emptyList()
    }

    private fun resolve(hostname: String, type: Int, dnsServer: String, useCache: Boolean): List<InetAddress> {
        val recordType = when (type) { Type.AAAA -> "AAAA"; Type.A -> "A"; else -> "Unknown" }

        val lookup = Lookup(hostname, type)
        val resolver = SimpleResolver(dnsServer).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                timeout = java.time.Duration.ofSeconds(timeoutSeconds.toLong())
            } else {
                @Suppress("DEPRECATION")
                setTimeout(timeoutSeconds)
            }
        }
        try {
            lookup.setResolver(resolver)
            lookup.setCache(if (useCache) dnsCache.get() else null)

            val records = lookup.run()
            Log.d(TAG, "Lookup $hostname ($recordType) with $dnsServer: result=${lookup.result}")

            return when (lookup.result) {
                Lookup.SUCCESSFUL -> records?.mapNotNull {
                    when (it) { is ARecord -> it.address; is AAAARecord -> it.address; else -> null }
                } ?: emptyList()
                Lookup.HOST_NOT_FOUND, Lookup.TYPE_NOT_FOUND -> emptyList()
                Lookup.TRY_AGAIN -> throw SocketTimeoutException("DNS server $dnsServer timeout")
                else -> throw UnknownHostException("DNS error: ${lookup.errorString}")
            }
        } finally {
            lookup.setResolver(null)
        }
    }
}