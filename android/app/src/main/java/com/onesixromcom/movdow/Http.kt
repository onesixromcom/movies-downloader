package com.onesixromcom.movdow

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Mobile operators / ISP DNS may not resolve video hosts (e.g. ashdi.vip),
 * so lookups go through DNS-over-HTTPS first and fall back to the system DNS.
 */
object Http {

    val client: OkHttpClient by lazy {
        val bootstrap = OkHttpClient.Builder().build()
        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .build()

        bootstrap.newBuilder()
            .dns(FallbackDns(doh, Dns.SYSTEM))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private class FallbackDns(private val primary: Dns, private val secondary: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try {
                primary.lookup(hostname)
            } catch (e: UnknownHostException) {
                secondary.lookup(hostname)
            }
    }
}
