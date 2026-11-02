@file:Suppress("unused")

package com.lagradost.cloudstream3.plugins

import android.content.Context
import java.io.File

/** An installed extension, as CloudStream lists them: its file, where it came from and its version. */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
)

/**
 * CloudStream's PluginManager, for extensions that look at the others (Ultima and the like combine every
 * installed extension's sources). Echo's extension loader fills it as it loads them.
 */
object PluginManager {
    @Volatile
    private var online: Array<PluginData> = emptyArray()
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, Plugin>()

    /** The extension being loaded right now, as CloudStream's crash report names it. */
    @Volatile
    var currentlyLoading: String? = null

    @JvmStatic
    fun getPluginsOnline(): Array<PluginData> = online

    @JvmStatic
    fun getPluginsLocal(): Array<PluginData> = emptyArray()

    @JvmStatic
    fun getPlugins(): Map<String, Plugin> = loaded

    @JvmStatic
    fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File =
        File(online.firstOrNull { it.internalName == internalName }?.filePath ?: return File(context.filesDir, internalName))

    /** Echo loads every installed extension itself; nothing extra is loaded on an extension's request. */
    @JvmStatic
    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean =
        online.any { it.internalName == apiName }

    /** Echo unloads extensions when they're removed on its Plugins page; an extension can't unload another. */
    @JvmStatic
    fun unloadPlugin(absolutePath: String) {}

    internal fun setOnline(plugins: List<PluginData>) {
        online = plugins.toTypedArray()
    }

    internal fun onLoaded(filePath: String, plugin: Plugin) {
        loaded[filePath] = plugin
    }

    internal fun onUnloaded(filePath: String) {
        loaded.remove(filePath)
    }
}
