package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.StateFlow

data class SimklAuthScope(
    val profileId: Int,
    val generation: Long
)

data class SimklAuthorization(
    val scope: SimklAuthScope,
    val accessToken: String,
    val refreshToken: String? = null
)

interface SimklAuthStorage {
    val state: StateFlow<SimklAuthState>

    fun currentScope(): SimklAuthScope
    fun isCurrent(scope: SimklAuthScope): Boolean
    fun authorization(): SimklAuthorization?
    fun accessToken(): String? = authorization()?.accessToken
    fun savePinSession(session: SimklPinSession, scope: SimklAuthScope = currentScope()): Boolean
    fun saveDeviceCode(code: String, scope: SimklAuthScope = currentScope()): Boolean = false
    fun deviceCode(scope: SimklAuthScope = currentScope()): String? = null
    fun clearPinSession(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun completePinAuthorization(token: String, scope: SimklAuthScope): Boolean
    fun completeV2Authorization(token: String, refreshToken: String, scope: SimklAuthScope): Boolean = completePinAuthorization(token, scope)
    fun saveRefreshedTokens(token: String, refreshToken: String, scope: SimklAuthScope, expectedToken: String): Boolean = false
    fun saveIdentity(
        username: String?,
        accountId: Long?,
        settingsActivityWatermark: String? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun recordSettingsActivityWatermark(
        watermark: String,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun clearAuth(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope(),
        expectedAccessToken: String? = null
    ): Boolean
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}
