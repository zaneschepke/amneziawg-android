package com.zaneschepke.droiddns

import java.net.UnknownHostException

interface DnsResolver {
    /**
     * Method to resolve hostnames.
     * @param hostname The hostname to resolve.
     * @param preferIpv4 Whether the resolver should prefer ipv4 over ipv6.
     * @param useCache Whether the resolver should use cached results or not.
     * @return A list of string values denoting resolved IP addresses.
     * @throws UnknownHostException
     */
    fun resolveDns(hostname: String, preferIpv4: Boolean = false, useCache: Boolean) : List<String>
}