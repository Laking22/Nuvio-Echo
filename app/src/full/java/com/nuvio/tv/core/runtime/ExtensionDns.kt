package com.nuvio.tv.core.runtime

import android.content.Context
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * The DNS CloudStream extensions look sites up with, as CloudStream offers it: the TV's own, or one of the same
 * DNS-over-HTTPS providers, for sites an internet provider's DNS blocks.
 */
object ExtensionDns {
    enum class Provider(
        val key: Int,
        @StringRes val labelRes: Int,
        val url: String?,
        val bootstrap: List<String>
    ) {
        SYSTEM(0, R.string.extension_dns_system, null, emptyList()),
        CLOUDFLARE(2, R.string.extension_dns_cloudflare, "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        GOOGLE(1, R.string.extension_dns_google, "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")),
        ADGUARD(4, R.string.extension_dns_adguard, "https://dns.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
        QUAD9(6, R.string.extension_dns_quad9, "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        DNS_SB(7, R.string.extension_dns_sb, "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11")),
        CANADIAN_SHIELD(8, R.string.extension_dns_canadian, "https://private.canadianshield.cira.ca/dns-query", listOf("149.112.121.10", "149.112.122.10"))
    }

    private const val PREFS = "extension_network"
    private const val KEY_DNS = "dns"

    fun current(context: Context): Provider {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DNS, Provider.SYSTEM.key)
        return Provider.entries.firstOrNull { it.key == key } ?: Provider.SYSTEM
    }

    /** Saves [provider] and rebuilds the extensions' HTTP client with it. */
    fun set(context: Context, provider: Provider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_DNS, provider.key).apply()
        PluginRuntimeHooks.rebuildExtensionClient()
    }

    /** The resolver for [provider], IPv4 first as the rest of the app resolves. */
    fun dnsFor(provider: Provider, bootstrapClient: OkHttpClient): Dns {
        val url = provider.url ?: return IPv4FirstDns()
        val doh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(provider.bootstrap.map { InetAddress.getByName(it) })
            .build()
        return IPv4FirstDns(doh)
    }
}
