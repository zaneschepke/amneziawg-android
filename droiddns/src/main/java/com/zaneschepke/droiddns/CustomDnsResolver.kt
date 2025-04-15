package com.zaneschepke.droiddns

import android.net.DnsResolver.*
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CustomDnsResolver : AndroidDnsResolver {

    companion object {
        private const val TAG = "CustomDnsResolver"
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

    private val executor: Executor = Dispatchers.IO.limitedParallelism(1).asExecutor()

    override suspend fun resolve(hostname: String, preferIpv4: Boolean, useCache: Boolean, network: Network?): List<InetAddress> = suspendCoroutine { continuation ->
        if (hostname.isBlank()) {
            continuation.resumeWithException(UnknownHostException("Hostname cannot be empty"))
            return@suspendCoroutine
        }

        val hostnameSanitized = hostname.removeSurrounding("[", "]")

        if (IPV4_REGEX.matches(hostnameSanitized) || IPV6_REGEX.matches(hostnameSanitized)) {
            Log.i(TAG, "Hostname already resolved: $hostnameSanitized. Returning as is.")
            try {
                continuation.resume(listOf(InetAddress.getByName(hostnameSanitized)))
            } catch (e: UnknownHostException) {
                Log.e(TAG, "Failed to parse IP: $hostnameSanitized", e)
                continuation.resumeWithException(e)
            }
            return@suspendCoroutine
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val flag = if (useCache) FLAG_EMPTY else FLAG_NO_CACHE_LOOKUP
            val dnsResolver = getInstance()
            val cancellationSignal = CancellationSignal()
            var isResumed = false

            val callback = object : Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    synchronized(this) {
                        if (isResumed) return
                        isResumed = true
                    }
                    try {
                        when {
                            rcode == 0 && answer.isNotEmpty() -> {
                                val filtered = if (preferIpv4) {
                                    val ipv4Addresses = answer.filterIsInstance<Inet4Address>()
                                    ipv4Addresses.ifEmpty { answer }
                                } else {
                                    answer
                                }
                                Log.d(TAG, "Filtered for Ipv4: $preferIpv4, resolved address: ${if (filtered.isNotEmpty()) filtered[0].hostAddress else "none"}")
                                continuation.resume(filtered)
                            }
                            rcode == 3 -> { // NXDOMAIN
                                continuation.resume(emptyList())
                            }
                            else -> {
                                continuation.resumeWithException(Exception("DNS query failed with rcode: $rcode"))
                            }
                        }
                    } finally {
                        cancellationSignal.cancel()
                    }
                }

                override fun onError(error: DnsException) {
                    synchronized(this) {
                        if (isResumed) return
                        isResumed = true
                    }
                    try {
                        continuation.resumeWithException(error)
                    } finally {
                        cancellationSignal.cancel()
                    }
                }
            }

            try {
                dnsResolver.query(
                    network,
                    hostname,
                    flag,
                    executor,
                    cancellationSignal,
                    callback
                )
            } catch (e: Exception) {
                synchronized(this) {
                    if (!isResumed) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        } else {
            try {
                val addresses = InetAddress.getAllByName(hostname)
                val filtered = if (preferIpv4) {
                    val ipv4Addresses = addresses.filterIsInstance<Inet4Address>()
                    ipv4Addresses.ifEmpty { addresses.toList() }
                } else {
                    addresses.toList()
                }
                Log.d(TAG, "Filtered for Ipv4: $preferIpv4, resolved address: ${if (filtered.isNotEmpty()) filtered[0].hostAddress else "none"}")
                continuation.resume(filtered)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    @Throws(UnknownHostException::class)
    override fun resolveBlocking(
        hostname: String,
        preferIpv4: Boolean,
        useCache: Boolean,
        network: Network?
    ): List<InetAddress> {
        return runBlocking(Dispatchers.IO) {
            resolve(hostname, preferIpv4, useCache, network)
        }
    }
}