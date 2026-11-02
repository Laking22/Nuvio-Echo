@file:Suppress("unused")

package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * CloudStream's MainActivity, for the app-wide events extensions listen to. Echo fires [afterPluginsLoadedEvent]
 * once its extensions have loaded; the home, library and bookmark events have no CloudStream screens behind them.
 */
class MainActivity {
    companion object {
        @JvmStatic
        val afterPluginsLoadedEvent = Event<Boolean>()

        @JvmStatic
        val mainPluginsLoadedEvent = Event<Boolean>()

        @JvmStatic
        val afterRepositoryLoadedEvent = Event<Boolean>()

        @JvmStatic
        val reloadHomeEvent = Event<Boolean>()

        @JvmStatic
        val reloadLibraryEvent = Event<Boolean>()

        @JvmStatic
        val reloadAccountEvent = Event<Boolean>()

        @JvmStatic
        val bookmarksUpdatedEvent = Event<Boolean>()
    }
}
