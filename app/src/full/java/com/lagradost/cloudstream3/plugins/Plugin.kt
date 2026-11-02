package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis

/**
 * The base class that CloudStream extensions extend in NuvioTV.
 * Kept standalone (not extending BasePlugin) because BasePlugin's registration
 * methods are final and can't be overridden. Extensions compiled against the
 * real CloudStream app reference this class directly.
 */
open class Plugin {
    private val _registeredMainAPIs = mutableListOf<MainAPI>()
    private val _registeredExtractorAPIs = mutableListOf<ExtractorApi>()

    val registeredMainAPIs: List<MainAPI> get() = _registeredMainAPIs
    val registeredExtractorAPIs: List<ExtractorApi> get() = _registeredExtractorAPIs

    /** The extension's settings screen, opened from its row on the Plugins page. */
    var openSettings: ((Context) -> Unit)? = null

    /** The extension's own resources (layouts, strings, drawables for its settings), when it ships any. */
    var resources: android.content.res.Resources? = null

    /** Called before the extension is unloaded (removed or updated). */
    @Throws(Throwable::class)
    open fun beforeUnload() {}

    /** Full file path to the plugin (matches BasePlugin's property). */
    var filename: String? = null

    /**
     * No-arg load matching BasePlugin's pattern. Extensions compiled against
     * CloudStream may override this instead of load(Activity?).
     */
    open fun load() {}

    /**
     * Called when the plugin is loaded. Override to register APIs.
     * The [activity] parameter may be null when loaded outside an Activity context.
     * Delegates to no-arg load() so BasePlugin-style extensions get invoked.
     */
    @Suppress("UNUSED_PARAMETER")
    open fun load(activity: Activity?) {
        load()
    }

    /**
     * As CloudStream's BasePlugin: the source goes on CloudStream's global lists too (allProviders, and apis with its
     * name index), so extensions that combine others find it by name. Only once, though the loader may record a
     * source its plugin already put there itself.
     */
    fun registerMainAPI(element: MainAPI) {
        Log.d("CS3Plugin", "registerMainAPI called: ${element.name} (${element.javaClass.name})")
        if (_registeredMainAPIs.none { it === element }) _registeredMainAPIs.add(element)
        element.sourcePlugin = this.filename
        val providers = com.lagradost.cloudstream3.APIHolder.allProviders
        val added = synchronized(providers) {
            if (providers.none { it === element }) providers.add(element) else false
        }
        if (added) {
            try {
                com.lagradost.cloudstream3.APIHolder.addPluginMapping(element)
            } catch (_: Exception) {}
        }
    }

    fun registerExtractorAPI(element: ExtractorApi) {
        Log.d("CS3Plugin", "registerExtractorAPI called: ${element.name} (${element.javaClass.name})")
        if (_registeredExtractorAPIs.none { it === element }) _registeredExtractorAPIs.add(element)
        element.sourcePlugin = this.filename
        if (extractorApis.none { it === element }) extractorApis.add(element)
    }

    // Some extensions call these overloads
    open fun load(context: Context) {
        load(context as? Activity)
    }
}
