package com.zaneschepke.droiddns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import org.xbill.DNS.*
import java.net.*
import java.util.concurrent.atomic.AtomicReference

class JavaDnsResolver @JvmOverloads constructor(
    context: Context,
    private val preferredDnsServer: String? = null,
    private val fallbackDnsServers: List<String> = listOf(DEFAULT_FALLBACK_DNS_IPV4, DEFAULT_FALLBACK_DNS_IPV6),
    private val timeoutSeconds: Int = 5,
    private val maxRetries: Int = 2,
    private val nat64Prefix: String? = "64:ff9b::" // Default well-known NAT64 prefix
) : DnsResolver {

    companion object {
        const val DEFAULT_FALLBACK_DNS_IPV4 = "1.1.1.1"
        const val DEFAULT_FALLBACK_DNS_IPV6 = "2606:4700:4700::1111"
        private val TAG = JavaDnsResolver::class.java.simpleName

        private val IPV6_REGEX = Regex(
            "(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:)" +
                    "{1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]"+
                    "{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:"+
                    "[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4})"+
                    "{1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}"+
                    ":((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]"+
                    "{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}"+
                    "[0-9]){0,1}[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:)"+
                    "{1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))"
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
        Log.d(
            TAG,
            "Initialized JavaDnsResolver with timeout=$timeoutSeconds, maxRetries=$maxRetries, nat64Prefix=$nat64Prefix"
        )
    }

    private val systemDnsServers: List<String> by lazy {
        val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
        linkProperties?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
    }


    @Throws(UnknownHostException::class)
    @Synchronized
    override fun resolveDns(hostname: String, preferIpv4: Boolean, useCache: Boolean): List<String> {
        Log.i(TAG, "Starting DNS resolution for hostname=$hostname, preferIpv4=$preferIpv4, useCache=$useCache")


        if (IPV4_REGEX.matches(hostname) || IPV6_REGEX.matches(hostname.removeSurrounding("[", "]"))) {
            Log.i(TAG, "Hostname already resolved: $hostname. Returning as is.")
            return listOf(hostname)
        }

        val dnsServers = getDnsServers()
        Log.d(TAG, "DNS servers to try: $dnsServers")

        var result: List<InetAddress> = emptyList()
        for (dnsServer in dnsServers) {
            Log.i(TAG, "Attempting resolution of $hostname with server: $dnsServer")
            result = tryResolve(hostname, dnsServer, preferIpv4, useCache)
            if (result.isNotEmpty()) {
                Log.i(TAG, "Successfully resolved $hostname to $result with server $dnsServer")
                break
            } else {
                Log.w(TAG, "Resolution failed with server $dnsServer, trying next")
            }
        }
        val finalResult = result.mapNotNull { it.hostAddress }
        Log.i(TAG, "Final resolved addresses for $hostname: $finalResult")
        return finalResult
    }

    private fun getDnsServers(): List<String> {
        return listOfNotNull(preferredDnsServer) +
                (if (preferredDnsServer == null) systemDnsServers else emptyList()) +
                (if (preferredDnsServer == null && systemDnsServers.isEmpty()) fallbackDnsServers else emptyList())
    }

    private fun tryResolve(
        hostname: String,
        dnsServer: String,
        preferIpv4: Boolean,
        useCache: Boolean
    ): List<InetAddress> {
        Log.d(TAG, "tryResolve: hostname=$hostname, dnsServer=$dnsServer, preferIpv4=$preferIpv4, useCache=$useCache")
        return when {
            preferIpv4 -> {
                Log.d(TAG, "Preferring IPv4, trying A records first")
                retryResolve(hostname, Type.A, dnsServer, useCache).ifEmpty {
                    Log.d(TAG, "A records empty, falling back to AAAA")
                    retryResolve(hostname, Type.AAAA, dnsServer, useCache)
                }
            }

            isIPv4OnlyNetwork() -> {
                Log.d(TAG, "IPv4-only network detected, skipping AAAA, resolving A records only")
                retryResolve(hostname, Type.A, dnsServer, useCache)
            }

            else -> {
                Log.d(TAG, "Preferring IPv6, trying AAAA records first")
                val aaaaResult = retryResolve(hostname, Type.AAAA, dnsServer, useCache)
                if (aaaaResult.isEmpty() && isIPv6OnlyNetwork()) {
                    Log.d(TAG, "AAAA empty and IPv6-only network, synthesizing IPv6 via DNS64")
                    synthesizeIPv6FromIPv4(hostname, dnsServer, useCache)
                } else {
                    Log.d(TAG, "AAAA result: $aaaaResult, falling back to A if empty")
                    aaaaResult.ifEmpty { retryResolve(hostname, Type.A, dnsServer, useCache) }
                }
            }
        }
    }

    private fun isIPv4Capable(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        val result = network != null && capabilities != null && linkProperties != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (linkProperties.dnsServers.any { it is Inet4Address } ||
                        linkProperties.linkAddresses.any { it.address is Inet4Address })
        Log.d(
            TAG,
            "isIPv4Capable: $result (dnsServers=${linkProperties?.dnsServers}, linkAddresses=${linkProperties?.linkAddresses})"
        )
        return result
    }

    private fun isIPv6Capable(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        val result = network != null && capabilities != null && linkProperties != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (linkProperties.dnsServers.any { it is Inet6Address } ||
                        linkProperties.linkAddresses.any { addr ->
                            addr.address is Inet6Address && !addr.address.isLinkLocalAddress
                        })
        Log.d(
            TAG,
            "isIPv6Capable: $result (dnsServers=${linkProperties?.dnsServers}, linkAddresses=${linkProperties?.linkAddresses})"
        )
        return result
    }

    private fun isIPv6OnlyNetwork(): Boolean {
        val result = isIPv6Capable() && !isIPv4Capable()
        Log.d(TAG, "isIPv6OnlyNetwork: $result")
        return result
    }

    private fun isIPv4OnlyNetwork(): Boolean {
        val result = isIPv4Capable() && !isIPv6Capable()
        Log.d(TAG, "isIPv4OnlyNetwork: $result")
        return result
    }

    private fun retryResolve(hostname: String, type: Int, dnsServer: String, useCache: Boolean): List<InetAddress> {
        Log.d(
            TAG,
            "retryResolve: hostname=$hostname, type=${if (type == Type.A) "A" else "AAAA"}, dnsServer=$dnsServer, useCache=$useCache"
        )
        var lastException: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val result = resolve(hostname, type, dnsServer, useCache)
                Log.d(TAG, "Resolved $hostname (type=${if (type == Type.A) "A" else "AAAA"}) to $result")
                return result
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep((1 shl attempt) * 1000L)
                }
            }
        }
        Log.w(TAG, "Failed to resolve $hostname after $maxRetries retries: ${lastException?.message}")
        return emptyList()
    }

    private fun resolve(hostname: String, type: Int, dnsServer: String, useCache: Boolean): List<InetAddress> {
        val recordType = when (type) {
            Type.AAAA -> "AAAA"; Type.A -> "A"; else -> "Unknown"
        }
        Log.d(TAG, "resolve: $hostname ($recordType) with $dnsServer")

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
            Log.d(
                TAG,
                "Lookup $hostname ($recordType) with $dnsServer: result=${lookup.result}, error=${lookup.errorString}"
            )

            return when (lookup.result) {
                Lookup.SUCCESSFUL -> records?.mapNotNull {
                    when (it) {
                        is ARecord -> it.address; is AAAARecord -> it.address; else -> null
                    }
                } ?: emptyList()

                Lookup.HOST_NOT_FOUND, Lookup.TYPE_NOT_FOUND -> emptyList()
                Lookup.TRY_AGAIN -> throw SocketTimeoutException("DNS server $dnsServer timeout")
                else -> throw UnknownHostException("DNS error: ${lookup.errorString}")
            }
        } finally {
            lookup.setResolver(null)
        }
    }

    private fun synthesizeIPv6FromIPv4(hostname: String, dnsServer: String, useCache: Boolean): List<InetAddress> {
        Log.d(TAG, "Synthesizing IPv6 from IPv4 for $hostname using prefix $nat64Prefix")
        val aRecords = retryResolve(hostname, Type.A, dnsServer, useCache)
        if (aRecords.isEmpty()) {
            Log.w(TAG, "No A records found to synthesize IPv6")
            return emptyList()
        }

        val prefix = nat64Prefix?.let { Inet6Address.getByName(it) } ?: Inet6Address.getByName("64:ff9b::")
        val prefixBytes = prefix.address.take(12).toByteArray() // /96 prefix is 12 bytes

        val synthesized = aRecords.mapNotNull { ipv4 ->
            try {
                val ipv4Bytes = ipv4.address
                val ipv6Bytes = prefixBytes + ipv4Bytes
                val synthesizedAddr = Inet6Address.getByAddress(null, ipv6Bytes)
                Log.d(TAG, "Synthesized $ipv4 to $synthesizedAddr")
                synthesizedAddr
            } catch (e: Exception) {
                Log.w(TAG, "Failed to synthesize IPv6 for $ipv4: ${e.message}")
                null
            }
        }
        return synthesized
    }
}