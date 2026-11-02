@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import com.lagradost.api.setContext
import java.lang.ref.WeakReference

/**
 * Stub for CloudStream's AcraApplication.
 * Extensions (e.g. Yflix) reference AcraApplication.context and the getKey/setKey helpers.
 * Its key helpers forward to CloudStreamApp's, which store as CloudStream does.
 */
open class AcraApplication {
    companion object {
        /** Application context stub. Extensions use this for PackageManager etc. */
        @JvmStatic
        var context: Context? = null
            set(value) {
                field = value
                // Sync with CloudStreamApp (newer extensions reference it directly)
                CloudStreamApp.context = value
                // Also set the library's context so WebViewResolver and other
                // library components can access it
                if (value != null) {
                    setContext(WeakReference(value))
                }
            }

        /**
         * Weak reference to the current Activity. CloudStream extensions
         * often require a non-null Activity in their load() method to
         * register MainAPIs. Set from MainActivity.onCreate().
         */
        private var activityRef: WeakReference<Activity>? = null

        @JvmStatic
        fun getActivity(): Activity? = activityRef?.get()

        @JvmStatic
        fun setActivity(activity: Activity?) {
            activityRef = if (activity != null) WeakReference(activity) else null
            // Update the library's context to the Activity (preferred for WebView)
            if (activity != null) {
                setContext(WeakReference(activity))
            }
        }

        // The deprecated name for CloudStreamApp's key helpers: same storage.

        @JvmStatic
        fun <T> getKey(path: String, key: String, default: T? = null): T? =
            CloudStreamApp.getKey<Any>(path, key)?.let {
                @Suppress("UNCHECKED_CAST")
                (it as? T) ?: default
            } ?: default

        @JvmStatic
        fun <T> getKey(key: String, default: T? = null): T? =
            CloudStreamApp.getKey<Any>(key)?.let {
                @Suppress("UNCHECKED_CAST")
                (it as? T) ?: default
            } ?: default

        @JvmStatic
        fun setKey(path: String, key: String, value: Any?) = CloudStreamApp.setKey(path, key, value)

        @JvmStatic
        fun setKey(key: String, value: Any?) = CloudStreamApp.setKey(key, value)

        @JvmStatic
        fun removeKeys(prefix: String) {
            CloudStreamApp.removeKeys(prefix)
        }

        @JvmStatic
        fun removeKey(path: String, key: String) = CloudStreamApp.removeKey(path, key)

        @JvmStatic
        fun removeKey(key: String) = CloudStreamApp.removeKey(key)
    }
}
