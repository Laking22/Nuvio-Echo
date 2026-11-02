@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.utils.UiText

/** CloudStream's CommonActivity, as extensions use it: the current activity, and toasts. */
object CommonActivity {
    private const val TAG = "CommonActivity"

    // An instance property, as upstream: extensions call CommonActivity.INSTANCE.getActivity().
    var activity: Activity? = null

    fun showToast(@StringRes message: Int, duration: Int? = null) {
        val act = activity ?: return
        showToast(act, act.getString(message), duration)
    }

    fun showToast(message: String?, duration: Int? = null) {
        showToast(activity, message, duration)
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        val act = activity ?: return
        showToast(act, message?.asStringNull(act), duration)
    }

    fun showToast(act: Activity?, text: UiText, duration: Int) {
        if (act == null) return
        showToast(act, text.asStringNull(act), duration)
    }

    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) {
        if (act == null) return
        showToast(act, act.getString(message), duration)
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (act == null || message == null) {
            Log.w(TAG, "invalid showToast act = $act message = $message")
            return
        }
        act.runOnUiThread {
            runCatching { Toast.makeText(act, message, duration ?: Toast.LENGTH_SHORT).show() }
        }
    }
}
