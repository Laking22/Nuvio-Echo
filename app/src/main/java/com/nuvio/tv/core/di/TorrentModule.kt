package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TorrentModule {

    @Provides
    @Singleton
    fun provideTorrentSettings(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): TorrentSettings = TorrentSettings(factory, profileManager)

    @Provides
    @Singleton
    fun provideTorrentService(
        @ApplicationContext context: Context,
        torrentSettings: TorrentSettings
    ): TorrentService = TorrentService(context, torrentSettings)
}
