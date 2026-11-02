@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.annotation.StringRes

/** CloudStream's UiText: words that are either a plain string or a string resource, resolved with a context. */
sealed class UiText {
    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value
    }

    class StringResource(@StringRes val resId: Int, val args: List<Any>) : UiText() {
        override fun toString(): String = "resId = $resId\nargs = ${args.toList().map { "(${it::class} = $it)" }}"
    }

    fun asStringNull(context: Context?): String? = try {
        asString(context ?: return null)
    } catch (_: Exception) {
        null
    }

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> context.getString(resId, *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
    }
}

fun txt(value: String?): UiText? = value?.let { UiText.DynamicString(it) }

fun txt(@StringRes resId: Int, vararg args: Any): UiText = UiText.StringResource(resId, args.toList())
