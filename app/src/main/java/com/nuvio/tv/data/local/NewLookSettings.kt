package com.nuvio.tv.data.local

/** How notifications (calendar, Remind me, updates) come in. */
enum class NotificationStyle(val key: String) {
    /** A small black island drops in from the top centre, opens to show the message, then rises away. */
    ISLAND("island"),
    /** The island grows out of the profile picture at the top-left and folds back into it. */
    PROFILE("profile"),
    OFF("off");

    companion object {
        fun fromKey(key: String?): NotificationStyle = entries.firstOrNull { it.key == key } ?: ISLAND
    }
}

/** What "Threshold for viewed" (Because You Watched) is measured in. */
enum class ViewedThresholdUnit(val key: String) {
    MINUTES("minutes"),
    PERCENT("percent");

    companion object {
        fun fromKey(key: String?): ViewedThresholdUnit = entries.firstOrNull { it.key == key } ?: MINUTES
    }
}
