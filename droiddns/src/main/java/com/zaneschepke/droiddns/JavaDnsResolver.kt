package com.zaneschepke.droiddns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import org.xbill.DNS.*
import java.net.*


/**
 * A simple JavaDns Resolver implementation for Android.
 *
 * @param context An Android {@link Context}
 * @param preferredDnsServer Optional hostname or IP of the preferred DNS server to use for the resolution, default will be system configured DNS.
 * @param fallbackDnsServer Optional hostname or IP of the fallback DNS server to use, should there be issues with the preferred. Defaults to Cloudflare if not set.
 */
class JavaDnsResolver(private val context: Context, private val preferredDnsServer : String? = null, private val fallbackDnsServer : String = DEFAULT_FALLBACK_DNS) : DnsResolver {
    constructor(context: Context) : this(context, null, DEFAULT_FALLBACK_DNS)

    companion object {
        const val DEFAULT_FALLBACK_DNS = "1.1.1.1"
        private val TAG = JavaDnsResolver::class.java.simpleName
    }

    @Throws(UnknownHostException::class)
    override fun resolveDns(hostname: String, preferIpv4: Boolean, useCache: Boolean): List<String> {
        val dnsServer = getDefaultDnsServer().hostAddress ?: DEFAULT_FALLBACK_DNS
        Log.i(TAG, "Resolving endpoint with server: $dnsServer")
        val resolved = if (preferIpv4) {
            resolveIpv4(hostname, dnsServer, useCache).ifEmpty {
                resolveIpv6(hostname, dnsServer, useCache)
            }
        } else {
            resolveIpv6(hostname, dnsServer, useCache).ifEmpty {
                resolveIpv4(hostname, dnsServer, useCache)
            }
        }
        return resolved.mapNotNull { it.hostAddress }
    }

    private fun getDefaultDnsServer(): InetAddress {
        val dnsServers = getSystemDnsServers()
        val preferred = preferredDnsServer?.let { InetAddress.getByName(it) }
        return when {
            preferred != null -> preferred
            dnsServers.any { !it.isAnyLocalAddress && it is Inet4Address } -> dnsServers.first { it is Inet4Address }
            dnsServers.isNotEmpty() -> dnsServers.first()
            else -> InetAddress.getByName(DEFAULT_FALLBACK_DNS)
        }
    }

    private fun getSystemDnsServers(): List<InetAddress> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return emptyList()
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

        val hasIpv6 = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))

        val dnsServers = mutableListOf<InetAddress>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val privateDnsServerName = linkProperties.privateDnsServerName
            if (linkProperties.isPrivateDnsActive && privateDnsServerName != null) {
                return try {
                    val privateDnsAddresses = resolveIpv4(privateDnsServerName, useCache = false)
                    dnsServers.addAll(
                        if (hasIpv6) privateDnsAddresses
                        else privateDnsAddresses.filterIsInstance<Inet4Address>()
                    )
                    if (dnsServers.isEmpty()) {
                        listOf(InetAddress.getByName(DEFAULT_FALLBACK_DNS))
                    } else {
                        dnsServers
                    }
                } catch (e: Exception) {
                    listOf(InetAddress.getByName(DEFAULT_FALLBACK_DNS))
                }
            }
        }

        dnsServers.addAll(
            if (hasIpv6) linkProperties.dnsServers
            else linkProperties.dnsServers.filterIsInstance<Inet4Address>()
        )

        return dnsServers
    }

    private fun resolveIpv6(hostname: String, dnsServer: String = fallbackDnsServer, useCache: Boolean): List<InetAddress> {
        return resolve(hostname, Type.AAAA, dnsServer, useCache)
    }

    private fun resolve(hostname: String, type: Int, dnsServer: String, useCache: Boolean): List<InetAddress> {
        val recordType = when (type) {
            Type.AAAA -> "AAAA"
            Type.A -> "A"
            else -> "Unknown"
        }

        try {
            val lookup = Lookup(hostname, type)
            val resolver = SimpleResolver(dnsServer)
            lookup.setResolver(resolver)
            lookup.setCache(if (useCache) Cache() else null)

            val records = lookup.run()
            return when (lookup.result) {
                Lookup.SUCCESSFUL -> records?.mapNotNull {
                    when (it) {
                        is ARecord -> it.address
                        is AAAARecord -> it.address
                        else -> null
                    }
                } ?: emptyList()
                Lookup.HOST_NOT_FOUND -> emptyList()
                Lookup.TRY_AGAIN -> {
                    Log.w(TAG, "Failed to resolve $hostname with $dnsServer (TRY_AGAIN)")
                    emptyList()
                }
                Lookup.TYPE_NOT_FOUND -> {
                    Log.w(TAG, "No $recordType records for $hostname")
                    emptyList()
                }
                else -> {
                    Log.w(TAG, "Unknown DNS error resolving $hostname")
                    emptyList()
                }
            }
        } catch (e: SocketException) {
            Log.w(TAG, "Network error (SocketException) with $dnsServer for $hostname: ${e.message}")
            if (dnsServer != DEFAULT_FALLBACK_DNS) {
                Log.i(TAG, "Retrying with fallback DNS: $DEFAULT_FALLBACK_DNS")
                return resolve(hostname, type, DEFAULT_FALLBACK_DNS, useCache)
            }
            return emptyList()
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout error with $dnsServer for $hostname: ${e.message}")
            if (dnsServer != DEFAULT_FALLBACK_DNS) {
                Log.i(TAG, "Retrying with fallback DNS: $DEFAULT_FALLBACK_DNS")
                return resolve(hostname, type, DEFAULT_FALLBACK_DNS, useCache)
            }
            return emptyList()
        } catch (e: TextParseException) {
            Log.w(TAG, "Invalid hostname format: $hostname")
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error resolving $hostname with $dnsServer: ${e.message}")
            return emptyList()
        }
    }

    private fun resolveIpv4(hostname: String, dnsServer: String = fallbackDnsServer, useCache: Boolean): List<InetAddress> {
        return resolve(hostname, Type.A, dnsServer, useCache)
    }
}