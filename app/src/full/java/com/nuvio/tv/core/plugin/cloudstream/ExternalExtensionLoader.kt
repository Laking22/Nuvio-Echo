package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.nuvio.tv.core.plugin.TestDiagnostics
import dalvik.system.DexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtensionLoader"

/**
 * Checks whether an instance looks like a CloudStream plugin by checking if it has
 * plugin-like methods. Covers three cases:
 * 1. Our Plugin class: load(Activity?), load(Context), getRegisteredMainAPIs()
 * 2. Library's BasePlugin: load() (no-arg), registerMainAPI()
 * 3. Foreign plugins with similar signatures
 */
private fun looksLikePlugin(instance: Any): Boolean {
    // Fast path: check if it's an instance of the library's BasePlugin
    if (instance is BasePlugin) return true

    val clazz = instance.javaClass
    // Check for any form of load() method
    val hasLoad = try {
        clazz.getMethod("load", Context::class.java) != null
    } catch (_: NoSuchMethodException) {
        try {
            clazz.getMethod("load", android.app.Activity::class.java) != null
        } catch (_: NoSuchMethodException) {
            try {
                // BasePlugin-style no-arg load()
                clazz.getMethod("load") != null
            } catch (_: NoSuchMethodException) {
                false
            }
        }
    }
    val hasRegisteredAPIs = try {
        clazz.getMethod("getRegisteredMainAPIs") != null
    } catch (_: NoSuchMethodException) {
        false
    }
    return hasLoad || hasRegisteredAPIs
}

/**
 * Wraps a plugin instance loaded from a foreign classloader or with a non-standard base class.
 * Handles three plugin patterns:
 * 1. Our Plugin: load(Activity?), load(Context), getRegisteredMainAPIs()
 * 2. Library's BasePlugin: load() no-arg, registers to APIHolder.allProviders + extractorApis
 * 3. Foreign plugins with similar signatures
 */
private class ReflectivePluginWrapper(private val foreignInstance: Any) : Plugin() {
    override fun load(activity: android.app.Activity?) {
        load(activity as? Context ?: AcraApplication.context ?: return)
    }

    override fun load(context: Context) {
        // Snapshot global registries before load() to detect what this plugin adds
        val providersBefore = synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.toList()
        }
        val extractorsBefore = extractorApis.toList()

        val clazz = foreignInstance.javaClass
        var loaded = false

        // Try load(Context) first
        try {
            val m = clazz.getMethod("load", Context::class.java)
            m.invoke(foreignInstance, context)
            loaded = true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is ClassCastException) {
                Log.d(TAG, "ReflectivePluginWrapper: load(Context) got ClassCastException, retrying with null Activity")
                try {
                    val m = clazz.getMethod("load", android.app.Activity::class.java)
                    m.invoke(foreignInstance, null)
                    loaded = true
                } catch (e2: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load(Activity) also failed: ${e2.message}")
                }
            } else {
                Log.w(TAG, "ReflectivePluginWrapper: load(Context) threw: ${cause?.message ?: e.message}")
            }
        } catch (_: NoSuchMethodException) {
            // Try load(Activity?) next
            try {
                val m = clazz.getMethod("load", android.app.Activity::class.java)
                m.invoke(foreignInstance, null)
                loaded = true
            } catch (_: NoSuchMethodException) {
                // Try no-arg load() (BasePlugin pattern)
                try {
                    val m = clazz.getMethod("load")
                    m.invoke(foreignInstance)
                    loaded = true
                    Log.d(TAG, "ReflectivePluginWrapper: loaded via no-arg load()")
                } catch (e: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load() (no-arg) failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
        }

        // First try reading local registration lists (our Plugin pattern)
        try {
            val getter = clazz.getMethod("getRegisteredMainAPIs")
            @Suppress("UNCHECKED_CAST")
            val apis = getter.invoke(foreignInstance) as? List<MainAPI> ?: emptyList()
            apis.forEach { registerMainAPI(it) }
        } catch (_: Exception) {
            // No local list — check global APIHolder for newly registered providers
            // (BasePlugin.registerMainAPI adds to APIHolder.allProviders)
            val newProviders = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.toList()
            } - providersBefore.toSet()
            if (newProviders.isNotEmpty()) {
                Log.d(TAG, "ReflectivePluginWrapper: found ${newProviders.size} providers via APIHolder")
                newProviders.forEach { registerMainAPI(it) }
            }
        }

        try {
            val getter = clazz.getMethod("getRegisteredExtractorAPIs")
            @Suppress("UNCHECKED_CAST")
            val extractors = getter.invoke(foreignInstance) as? List<ExtractorApi> ?: emptyList()
            extractors.forEach { registerExtractorAPI(it) }
        } catch (_: Exception) {
            // Check global extractorApis for newly registered extractors
            val newExtractors = extractorApis.toList() - extractorsBefore.toSet()
            if (newExtractors.isNotEmpty()) {
                Log.d(TAG, "ReflectivePluginWrapper: found ${newExtractors.size} extractors via extractorApis")
                newExtractors.forEach { registerExtractorAPI(it) }
            }
        }
    }
}

private const val MAX_DEX_SIZE = 10 * 1024 * 1024L // 10MB max per .cs3 file

/**
 * Manages downloading, loading, and caching of DEX-based external extensions (.cs3 files).
 */
@Singleton
class ExternalExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractorRegistry: ExternalExtractorRegistry
) {
    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Cache of loaded class loaders by scraper ID */
    private val classLoaderCache = ConcurrentHashMap<String, DexClassLoader>()

    private val extensionsDir: File
        get() = File(context.filesDir, "cs_extensions").also { it.mkdirs() }

    private val codeCacheDir: File
        get() = File(context.codeCacheDir, "cs_dex_cache").also { it.mkdirs() }

    /** Sanitize scraper ID for use as a filename (colons are not safe on all filesystems). */
    private fun safeFileName(scraperId: String): String =
        scraperId.replace(':', '_').replace('/', '_')

    /**
     * Download a .cs3 DEX file for the given scraper.
     * Returns the local file path, or null on failure. The installed copy is only replaced (and unloaded, so the
     * next use loads the new one) once the new one has fully downloaded: a failed update keeps the extension working.
     */
    suspend fun downloadExtension(scraperId: String, downloadUrl: String): File? = withContext(Dispatchers.IO) {
        com.nuvio.tv.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()
        try {
            val targetFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download extension $scraperId: HTTP ${response.code}")
                    return@withContext null
                }

                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
                if (contentLength > MAX_DEX_SIZE) {
                    Log.w(TAG, "Extension $scraperId too large: $contentLength bytes")
                    return@withContext null
                }

                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size > MAX_DEX_SIZE) {
                    Log.w(TAG, "Extension $scraperId too large: ${bytes.size} bytes")
                    return@withContext null
                }

                val partFile = File(extensionsDir, "${targetFile.name}.part")
                partFile.delete()
                partFile.writeBytes(bytes)

                // The old copy goes only now. DEX files are read-only (API 28+), so it's made writable to delete.
                unload(scraperId)
                if (targetFile.exists()) {
                    targetFile.setWritable(true)
                    targetFile.delete()
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.delete()
                    Log.e(TAG, "Couldn't install downloaded extension $scraperId")
                    return@withContext null
                }

                // Fix for Android API 28+: DEX files must be read-only
                // Writing writable DEX files is blocked on newer Android versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    targetFile.setReadOnly()
                    Log.d(TAG, "Set DEX file read-only for API ${Build.VERSION.SDK_INT}")
                }

                Log.d(TAG, "Downloaded extension $scraperId: ${bytes.size} bytes -> ${targetFile.absolutePath}")
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading extension $scraperId: ${e.message}", e)
            null
        }
    }

    /** One installed extension, loaded once: its class loader, its plugin, and what the plugin registered. */
    class LoadedExtension(
        val scraperId: String,
        val file: File,
        val classLoader: DexClassLoader,
        val plugin: Plugin?,
        val apis: List<MainAPI>,
        val extractors: List<ExtractorApi>,
        /** Why it couldn't be used, when [apis] is empty. */
        val failure: String?
    )

    private val loaded = ConcurrentHashMap<String, LoadedExtension>()
    private val loadLocks = ConcurrentHashMap<String, Any>()

    /** Done once the startup load has loaded the enabled extensions, for [awaitStartupLoad]. */
    private val startupLoad = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun isLoaded(scraperId: String): Boolean = loaded.containsKey(scraperId)

    /** Where [scraperId]'s downloaded extension file lives. */
    fun extensionFile(scraperId: String): File = File(extensionsDir, "${safeFileName(scraperId)}.cs3")

    /**
     * Loads [scraperId]'s extension, once per session, as CloudStream does at startup: a class loader for its
     * file, its plugin's load(), and the sources and extractors it registers added to CloudStream's global lists
     * (so extensions that combine others, and loadExtractor, see them). Later calls return the same result.
     * Only when the plugin registers nothing at all is its file scanned for source and extractor classes.
     */
    fun load(scraperId: String, diagnostics: TestDiagnostics? = null): LoadedExtension? {
        loaded[scraperId]?.let {
            diagnostics?.addStep("Loaded already: ${it.apis.size} sources, ${it.extractors.size} extractors")
            return it
        }
        synchronized(loadLocks.getOrPut(scraperId) { Any() }) {
            loaded[scraperId]?.let { return it }
            val result = loadUncached(scraperId, diagnostics) ?: return null
            loaded[scraperId] = result
            return result
        }
    }

    private fun loadUncached(scraperId: String, diagnostics: TestDiagnostics?): LoadedExtension? {
        com.nuvio.tv.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()
        val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (!dexFile.exists()) {
            Log.e(TAG, "DEX file not found for $scraperId: ${dexFile.absolutePath}")
            diagnostics?.addStep("DEX file NOT FOUND: ${dexFile.name}")
            return null
        }
        diagnostics?.addStep("DEX: ${dexFile.length()} bytes")
        ensureDexReadOnly(dexFile)
        AcraApplication.context = context
        extractorRegistry.installGlobal()

        val startedAt = System.currentTimeMillis()
        val classLoader = classLoaderCache.getOrPut(scraperId) {
            DexClassLoader(dexFile.absolutePath, codeCacheDir.absolutePath, null, context.classLoader)
        }
        val plugin = try {
            findAndLoadPlugin(classLoader, dexFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to find plugin class in $scraperId: ${e.message}", e)
            null
        }
        var failure: String? = null
        if (plugin == null) {
            diagnostics?.addStep("No @CloudstreamPlugin class found in DEX")
        } else {
            diagnostics?.addStep("Plugin: ${plugin.javaClass.simpleName}, isWrapper=${plugin is ReflectivePluginWrapper}")
            plugin.filename = dexFile.absolutePath
            plugin.resources = loadResources(dexFile)
            com.lagradost.cloudstream3.plugins.PluginManager.currentlyLoading = scraperId
            try {
                plugin.load((AcraApplication.getActivity() as Context?) ?: context)
                diagnostics?.addStep("load(): OK, ${plugin.registeredMainAPIs.size} sources, ${plugin.registeredExtractorAPIs.size} extractors")
            } catch (e: Throwable) {
                val missing = (e as? Error)?.let(::extractMissingClassName)
                failure = missing?.let { "missing class $it" } ?: "${e.javaClass.simpleName}: ${e.message?.take(160)}"
                Log.w(TAG, "plugin.load() failed for $scraperId (${plugin.registeredMainAPIs.size} sources so far): $failure", e)
                diagnostics?.addStep("load() FAILED: $failure")
            } finally {
                com.lagradost.cloudstream3.plugins.PluginManager.currentlyLoading = null
            }
            com.lagradost.cloudstream3.plugins.PluginManager.onLoaded(dexFile.absolutePath, plugin)
        }

        var apis: List<MainAPI> = plugin?.registeredMainAPIs.orEmpty()
        var extractors: List<ExtractorApi> = plugin?.registeredExtractorAPIs.orEmpty()
        if (apis.isEmpty() && extractors.isEmpty()) {
            diagnostics?.addStep("Plugin registered nothing: scanning DEX for source and extractor classes")
            val (scannedApis, scannedExtractors) = scanForApis(classLoader, dexFile, diagnostics)
            apis = scannedApis
            extractors = scannedExtractors
            scannedApis.forEach { api ->
                api.sourcePlugin = dexFile.absolutePath
                runCatching { APIHolder.allProviders.add(api) }
                runCatching { APIHolder.addPluginMapping(api) }
            }
        }
        extractorRegistry.registerAll(extractors)
        if (apis.isNotEmpty()) failure = null else if (failure == null) failure = "registered no sources"

        Log.i(
            TAG,
            "Loaded extension $scraperId in ${System.currentTimeMillis() - startedAt}ms: ${apis.size} sources " +
                "(${apis.joinToString { it.name }}), ${extractors.size} extractors${failure?.let { ", $it" } ?: ""}"
        )
        return LoadedExtension(scraperId, dexFile, classLoader, plugin, apis, extractors, failure)
    }

    /** CloudStream gives an extension that ships resources (its settings layouts) a Resources over its own file. */
    @Suppress("DEPRECATION")
    private fun loadResources(dexFile: File): android.content.res.Resources? {
        val requires = runCatching {
            ZipFile(dexFile).use { zip ->
                zip.getEntry("manifest.json")?.let { entry ->
                    JSONObject(zip.getInputStream(entry).bufferedReader().readText()).optBoolean("requiresResources", false)
                } ?: false
            }
        }.getOrDefault(false)
        if (!requires) return null
        return runCatching {
            val assets = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
            android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                .invoke(assets, dexFile.absolutePath)
            android.content.res.Resources(assets, context.resources.displayMetrics, context.resources.configuration)
        }.onFailure { Log.w(TAG, "Couldn't load resources for ${dexFile.name}: ${it.message}") }.getOrNull()
    }

    /** The source and extractor classes in [dexFile], for a plugin whose load() registered none. */
    private fun scanForApis(
        classLoader: DexClassLoader,
        dexFile: File,
        diagnostics: TestDiagnostics?
    ): Pair<List<MainAPI>, List<ExtractorApi>> {
        val apis = mutableListOf<MainAPI>()
        val extractors = mutableListOf<ExtractorApi>()
        val classes = try {
            @Suppress("DEPRECATION")
            dalvik.system.DexFile(dexFile).let { dex -> dex.entries().toList().also { dex.close() } }
        } catch (e: Exception) {
            diagnostics?.addStep("DEX inspection failed: ${e.message?.take(100)}")
            return apis to extractors
        }
        for (className in classes) {
            if (className.contains('$') || className.startsWith("com.lagradost.")) continue
            try {
                val clazz = classLoader.loadClass(className)
                if (clazz.isInterface || java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) continue
                when {
                    MainAPI::class.java.isAssignableFrom(clazz) ->
                        apis += (clazz.getDeclaredConstructor().newInstance() as MainAPI).also {
                            diagnostics?.addStep("Found source: ${it.name} (${clazz.simpleName})")
                        }
                    ExtractorApi::class.java.isAssignableFrom(clazz) ->
                        extractors += clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                }
            } catch (e: Throwable) {
                val missing = (e as? Error ?: e.cause as? Error)?.let(::extractMissingClassName)
                if (missing != null) diagnostics?.addStep("${className.substringAfterLast('.')}: MISSING $missing")
            }
        }
        return apis to extractors
    }

    /**
     * Loads installed extensions in the background, one at a time, as CloudStream does when it starts: [first]
     * (the enabled ones), then [then] (the rest, whose extractors other extensions may use). Sources waiting in
     * [awaitStartupLoad] go once [first] is done; extensions listening on MainActivity.afterPluginsLoadedEvent
     * hear once all are. Anything asked for meanwhile loads on demand.
     */
    fun loadAll(first: List<String>, then: List<String> = emptyList()) {
        loadEach(first)
        publishProviders()
        startupLoad.complete(Unit)
        loadEach(then)
        publishProviders()
        runCatching { com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent.invoke(false) }
    }

    /**
     * Loads extensions that were just updated (a repo refresh replaced their files), so their extractors are back
     * for the others straight away rather than when each is next used.
     */
    fun reload(ids: List<String>) {
        if (ids.isEmpty()) return
        loadEach(ids)
        publishProviders()
        runCatching { com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent.invoke(false) }
    }

    private fun loadEach(ids: List<String>) = ids.forEach { id ->
        try {
            load(id)
        } catch (e: Throwable) {
            Log.e(TAG, "Loading $id failed: ${e.message}", e)
        }
    }

    /**
     * As CloudStream does once its plugins load: APIHolder.apis becomes every distinct loaded source. Rebuilt through
     * addPluginMapping, the only way to rebuild its name index: setting the list alone left the index pointing at
     * the old positions, so an extension looking another's source up by name got the wrong one.
     */
    private fun publishProviders() {
        runCatching {
            val distinct = synchronized(APIHolder.allProviders) { APIHolder.allProviders.toList() }
                .distinctBy { it.lang + it.name + it.mainUrl + it.javaClass.name }
            if (distinct == APIHolder.apis.toList()) return@runCatching
            APIHolder.apis = com.lagradost.cloudstream3.utils.AtomicList(emptyList())
            distinct.forEach { APIHolder.addPluginMapping(it) }
        }.onFailure { Log.w(TAG, "Couldn't publish providers: ${it.message}") }
    }

    /** Waits (up to [timeoutMs]) for the startup load, so extractors other extensions provide are in place. */
    suspend fun awaitStartupLoad(timeoutMs: Long) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { startupLoad.await() }
    }

    /** The sources [scraperId]'s extension provides, loading it if it hasn't been. */
    fun getApis(scraperId: String): List<MainAPI> = load(scraperId)?.apis.orEmpty()

    /** Unloads [scraperId]'s extension: its plugin's beforeUnload(), and its sources and extractors taken out. */
    private fun unload(scraperId: String) {
        val ext = loaded.remove(scraperId) ?: run {
            classLoaderCache.remove(scraperId)
            return
        }
        runCatching { ext.plugin?.beforeUnload() }
        ext.apis.forEach { api ->
            runCatching { APIHolder.removePluginMapping(api) }
            runCatching { APIHolder.allProviders.remove(api) }
        }
        ext.extractors.forEach { extractor -> runCatching { extractorApis.remove(extractor) } }
        com.lagradost.cloudstream3.plugins.PluginManager.onUnloaded(ext.file.absolutePath)
        classLoaderCache.remove(scraperId)
    }

    fun deleteExtension(scraperId: String) {
        unload(scraperId)
        File(extensionsDir, "${safeFileName(scraperId)}.cs3").delete()
        Log.d(TAG, "Deleted extension $scraperId")
    }

    /** Drops [scraperId]'s loaded extension (it's being updated); the next use loads the new file. */
    fun evictCache(scraperId: String) {
        unload(scraperId)
    }

    /**
     * Ensure a DEX file is read-only. Required for Android API 28+ which blocks
     * writable DEX file loading.
     */
    private fun ensureDexReadOnly(dexFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dexFile.canWrite()) {
            dexFile.setReadOnly()
            Log.d(TAG, "Fixed DEX permissions (set read-only): ${dexFile.name}")
        }
    }

    private fun extractMissingClassName(e: Error): String? {
        val msg = e.message ?: return null
        val match = Regex("""(?:L?)([\w/.]+)(?:;)?""").find(msg)
        return match?.groupValues?.get(1)?.replace('/', '.')
    }

    private fun findAndLoadPlugin(classLoader: DexClassLoader, cs3File: File): Plugin? {
        val pluginClassName = readPluginClassNameFromZip(cs3File)
        if (pluginClassName != null) {
            try {
                Log.d(TAG, "Loading plugin class from manifest: $pluginClassName")
                val clazz = classLoader.loadClass(pluginClassName)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Plugin) {
                    return instance
                }
                if (looksLikePlugin(instance)) {
                    Log.d(TAG, "Using reflective wrapper for $pluginClassName (non-standard base class)")
                    return ReflectivePluginWrapper(instance)
                }
                Log.w(TAG, "Class $pluginClassName is not a Plugin and has no plugin methods")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest class $pluginClassName: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Linkage error loading manifest class $pluginClassName: ${e.message}", e)
            }
        }

        return scanForPluginClass(classLoader, cs3File)
    }

    private fun readPluginClassNameFromZip(cs3File: File): String? {
        return try {
            ZipFile(cs3File).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return null
                val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                val obj = JSONObject(json)
                obj.optString("pluginClassName", null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read manifest.json from ZIP: ${e.message}")
            null
        }
    }

    private fun scanForPluginClass(classLoader: DexClassLoader, cs3File: File): Plugin? {
        try {
            @Suppress("DEPRECATION")
            val dex = dalvik.system.DexFile(cs3File)
            val entries = dex.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        Log.d(TAG, "Found plugin class via scan: $className")
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is Plugin) {
                            dex.close()
                            return instance
                        }
                        if (looksLikePlugin(instance)) {
                            Log.d(TAG, "Using reflective wrapper for $className (non-standard base class)")
                            dex.close()
                            return ReflectivePluginWrapper(instance)
                        }
                        Log.w(TAG, "Annotated class $className has no plugin methods")
                    }
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }

            dex.close()
        } catch (e: Exception) {
            Log.d(TAG, "DexFile scan fallback failed: ${e.message}")
        }

        return null
    }
}
