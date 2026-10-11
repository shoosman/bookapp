package com.shoosman.bookapp.net

import com.shoosman.bookapp.CrashLog
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Общий HTTP-клиент. Если системный DNS не может найти адрес (провайдер, VPN,
 * «частный DNS» блокировщика), спрашиваем адрес через DNS-over-HTTPS у Cloudflare или Google.
 */
object Http {

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cloudflare: Dns = DnsOverHttps.Builder()
        .client(bootstrap)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
        .build()

    private val google: Dns = DnsOverHttps.Builder()
        .client(bootstrap)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
        .build()

    private object FallbackDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                CrashLog.note("DNS: системный не нашёл $hostname, пробую DoH")
            }
            for (dns in listOf(cloudflare, google)) {
                val found = runCatching { dns.lookup(hostname) }.getOrNull()
                if (!found.isNullOrEmpty()) return found
            }
            throw UnknownHostException("Не удалось найти адрес $hostname — проверьте интернет или VPN")
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
