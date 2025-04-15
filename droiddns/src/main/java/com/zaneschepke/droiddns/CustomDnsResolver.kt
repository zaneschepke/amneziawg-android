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
    }

    private val executor: Executor = Dispatchers.IO.limitedParallelism(1).asExecutor()

    override suspend fun resolve(hostname: String, preferIpv4: Boolean, useCache: Boolean, network: Network?): List<InetAddress> = suspendCoroutine { continuation ->
        if (hostname.isBlank()) {
            continuation.resumeWithException(UnknownHostException("Hostname cannot be empty"))
            return@suspendCoroutine
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val flag = if(useCache) FLAG_EMPTY else FLAG_NO_CACHE_LOOKUP
            val dnsResolver = getInstance()
            val cancellationSignal = CancellationSignal()

            val callback = object : Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
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
                    try {
                        continuation.resumeWithException(error)
                    } finally {
                        cancellationSignal.cancel()
                    }
                }
            }

            dnsResolver.query(
                network,
                hostname,
                flag,
                executor,
                cancellationSignal,
                callback
            )
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