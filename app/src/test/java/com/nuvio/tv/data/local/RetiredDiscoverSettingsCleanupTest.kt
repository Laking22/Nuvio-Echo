package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredDiscoverSettingsCleanupTest {

    private val otherKey = booleanPreferencesKey("hero_section_enabled")

    @Test
    fun `runs while any of the removed Discover settings is saved`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[discoverLocationKey] = "IN_SIDEBAR"
        }.toPreferences()
        assertTrue(retiredDiscoverSettingsCleanup.shouldMigrate(prefs))
        assertFalse(retiredDiscoverSettingsCleanup.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun `clears the removed Discover settings and keeps the rest`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacySearchDiscoverEnabledKey] = true
            this[discoverLocationKey] = "IN_SEARCH"
            this[lastNonOffDiscoverLocationKey] = "IN_SIDEBAR"
            this[otherKey] = false
        }.toPreferences()

        val cleaned = retiredDiscoverSettingsCleanup.migrate(prefs)

        assertNull(cleaned[legacySearchDiscoverEnabledKey])
        assertNull(cleaned[discoverLocationKey])
        assertNull(cleaned[lastNonOffDiscoverLocationKey])
        assertEquals(false, cleaned[otherKey])
        assertFalse(retiredDiscoverSettingsCleanup.shouldMigrate(cleaned))
    }
}
