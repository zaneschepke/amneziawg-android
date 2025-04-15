package com.zaneschepke.droiddns

import android.net.Network
import java.net.InetAddress
import java.net.UnknownHostException

interface AndroidDnsResolver {
    /**
     * Method to resolve hostnames.
     * @param hostname The hostname to resolve.
     * @param preferIpv4 Whether the resolver should prefer ipv4 over ipv6.
     * @param useCache Whether the resolver should use cached results or not, only works for >= 29.
     * @param network Whether the resolver should use a specific network or not, only works for >= 29.
     * @return A list of InetAddress values, ipv6 will be returned as fallback if no ipv4 available.
     * @throws UnknownHostException
     */
    @Throws(UnknownHostException::class)
    suspend fun resolve(hostname: String, preferIpv4: Boolean = false, useCache: Boolean, network: Network? = null) : List<InetAddress>

    /**
     * Method to resolve hostnames, sync.
     * @param hostname The hostname to resolve.
     * @param preferIpv4 Whether the resolver should prefer ipv4 over ipv6.
     * @param useCache Whether the resolver should use cached results or not, only works for >= 29.
     * @param network Whether the resolver should use a specific network or not, only works for >= 29.
     * @return A list of InetAddress values, ipv6 will be returned as fallback if no ipv4 available.
     * @throws UnknownHostException
     */
    @Throws(Exception::class)
    fun resolveBlocking(
        hostname: String,
        preferIpv4: Boolean,
        useCache: Boolean,
        network: Network?
    ): List<InetAddress>
}