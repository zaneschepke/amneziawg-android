package com.zaneschepke.droiddns

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import org.xbill.DNS.*
import java.net.InetAddress
import java.net.UnknownHostException



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
    override fun resolveDns(hostname: String, preferIpv4: Boolean, useCache: Boolean) : List<String> {
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

    private fun getDefaultDnsServer() : InetAddress {
        return preferredDnsServer?.let { InetAddress.getByName(it) } ?: getSystemDnsServers().firstOrNull() ?: InetAddress.getByName(DEFAULT_FALLBACK_DNS)
    }

    private fun getSystemDnsServers(): List<InetAddress> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return emptyList()

        val dnsServers = mutableListOf<InetAddress>()

        // Check for Private DNS (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val privateDnsServerName = linkProperties.privateDnsServerName
            if (linkProperties.isPrivateDnsActive && privateDnsServerName != null) {
                return try {
                    val privateDnsAddresses = resolveIpv4(privateDnsServerName, useCache = false)
                    dnsServers.addAll(privateDnsAddresses)
                    dnsServers
                } catch (e: Exception) {
                    listOf(InetAddress.getByName(DEFAULT_FALLBACK_DNS))
                }
            }
        }

        dnsServers.addAll(linkProperties.dnsServers)
        return dnsServers
    }

    private fun resolveIpv6(hostname: String, dnsServer: String = fallbackDnsServer, useCache: Boolean): List<InetAddress> {
        return resolve(hostname, Type.AAAA, dnsServer, useCache)
    }

    private fun resolve(hostname: String, type: Int, dnsServer: String, useCache: Boolean): List<InetAddress> {

        val recordType = when(type) {
            Type.AAAA -> "AAAA"
            Type.A -> "A"
            else -> "Unknown"
        }

        val lookup = Lookup(hostname, type)
        val resolver = SimpleResolver(dnsServer)
        lookup.setResolver(resolver)
        lookup.setCache(if(useCache) Cache() else null)

        val records = lookup.run()
        return when (lookup.result) {
            Lookup.SUCCESSFUL -> records?.map { (it as ARecord).address } ?: emptyList()
            Lookup.HOST_NOT_FOUND -> emptyList()
            Lookup.TRY_AGAIN -> {
                Log.w(TAG,"Failed to resolve $hostname with $dnsServer")
                emptyList()
            }
            Lookup.TYPE_NOT_FOUND -> {
                Log.w(TAG, "No $recordType records for $hostname")
                emptyList()
            }
            else -> {
                Log.w(TAG,"Unknown DNS error resolving $hostname")
                emptyList()
            }
        }
    }

    private fun resolveIpv4(hostname: String, dnsServer: String = fallbackDnsServer, useCache: Boolean): List<InetAddress> {
        return resolve(hostname, Type.A, dnsServer, useCache)
    }
}