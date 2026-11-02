@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import java.lang.ref.WeakReference

/**
 * Stub for CloudStream's CloudStreamApp class.
 *
 * In the real CloudStream app this is the Application class. Extensions
 * reference its companion fields (context, getKey/setKey helpers, etc.), and the key helpers really store,
 * as CloudStream's do.
 * Some plugins (e.g. AniDb) also reference cfUserAgent. Without this stub
 * the class fails to resolve at runtime, crashing the app with
 * NoClassDefFoundError inside OkHttp interceptors running on async threads.
 */
open class CloudStreamApp {
    companion object {
        /** Cloudflare bypass user-agent used by some extensions. */
        @JvmStatic
        var cfUserAgent: String = try {
            USER_AGENT
        } catch (_: Throwable) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        private var _context: WeakReference<Context>? = null

        @JvmStatic
        var context: Context?
            get() = _context?.get()
            set(value) {
                _context = if (value != null) WeakReference(value) else null
            }

        /** Retrieve Activity from Context (matches upstream tailrec helper). */
        @JvmStatic
        fun Context.getActivity(): Activity? {
            var ctx: Context? = this
            while (ctx != null) {
                if (ctx is Activity) return ctx
                ctx = (ctx as? ContextWrapper)?.baseContext
            }
            return null
        }

        // --- Extension storage: the same SharedPreferences file and JSON values as CloudStream's DataStore ---
        // Extensions save their settings, tokens and cached domains here; inline getKey reads go straight to
        // DataStore.getSharedPrefs, so these writes must land in the same place.

        @JvmStatic
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = context?.getKey(path, valueType)

        @JvmStatic
        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        @JvmStatic
        fun removeKeys(folder: String): Int? = context?.removeKeys(folder)

        @JvmStatic
        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        @JvmStatic
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = context?.getKey(path, defVal)

        @JvmStatic
        inline fun <reified T : Any> getKey(path: String): T? = context?.getKey(path)

        @JvmStatic
        inline fun <reified T : Any> getKey(folder: String, path: String): T? = context?.getKey(folder, path)

        @JvmStatic
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            context?.getKey(folder, path, defVal)

        @JvmStatic
        fun getKeys(folder: String): List<String>? = context?.getKeys(folder)

        @JvmStatic
        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        @JvmStatic
        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** Opens [url] in the TV's browser, if it has one. */
        @JvmStatic
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: androidx.fragment.app.Fragment? = null) {
            val ctx = fragment?.context ?: CommonActivity.activity ?: context ?: return
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        @JvmStatic
        fun openBrowser(url: String, activity: Activity?) {
            openBrowser(url, true, (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.fragments?.lastOrNull())
        }
    }
}
