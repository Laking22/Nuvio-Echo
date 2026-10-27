package com.nuvio.tv.core.player.seekpreview

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What seek thumbnails learned about servers: those that refuse range requests aren't tried again, those that gave
 * thumbnails are tried first, and those that didn't answer go last. Kept for a week across launches, so a fresh
 * start doesn't wait on the same dead servers again. Another source that failed (wrong length, no answer) isn't
 * tried again while the app runs, e.g. after a quality switch.
 */
object SeekPreviewHosts {
    private const val PREFS = "seek_preview_hosts"
    private const val KEY = "hosts"
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_HOSTS = 200
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private enum class Kind(val tag: String) { REFUSED("r"), WORKED("w"), SILENT("s") }

    /** Host to what it did and when. */
    private val hosts = ConcurrentHashMap<String, Pair<Kind, Long>>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var prefs: SharedPreferences? = null

    /** Loads what earlier launches learned; cheap after the first call. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            p.getStringSet(KEY, emptySet()).orEmpty().forEach { entry ->
                val parts = entry.split('|')
                if (parts.size != 3) return@forEach
                val kind = Kind.entries.firstOrNull { it.tag == parts[0] } ?: return@forEach
                val at = parts[2].toLongOrNull() ?: return@forEach
                if (now - at < KEEP_MS) hosts.putIfAbsent(parts[1], kind to at)
            }
            prefs = p
        }
    }

    fun refuses(url: String): Boolean = kindOf(url) == Kind.REFUSED

    fun worked(url: String): Boolean = kindOf(url) == Kind.WORKED

    /** The server didn't answer last time: tried after the others. */
    fun silent(url: String): Boolean = kindOf(url) == Kind.SILENT

    fun rejected(url: String): Boolean = url in rejected

    internal fun noteRejected(url: String) {
        rejected += url
    }

    internal fun noteRefused(url: String) {
        host(url)?.let { if (hosts[it]?.first != Kind.WORKED) put(it, Kind.REFUSED) }
    }

    internal fun noteSilent(url: String) {
        host(url)?.let { if (hosts[it]?.first != Kind.WORKED) put(it, Kind.SILENT) }
    }

    internal fun noteWorked(url: String) {
        host(url)?.let { put(it, Kind.WORKED) }
    }

    private fun kindOf(url: String): Kind? = host(url)?.let { hosts[it]?.first }

    private fun put(host: String, kind: Kind) {
        val now = System.currentTimeMillis()
        val previous = hosts[host]
        // Unchanged and noted today: nothing new to save.
        if (previous?.first == kind && now - previous.second < DAY_MS) return
        hosts[host] = kind to now
        val p = prefs ?: return
        val entries = hosts.entries.sortedByDescending { it.value.second }.take(MAX_HOSTS)
            .map { "${it.value.first.tag}|${it.key}|${it.value.second}" }.toSet()
        p.edit().putStringSet(KEY, entries).apply()
    }

    private fun host(url: String): String? = url.toHttpUrlOrNull()?.host
}
