package net.asksakis.massdroidv2.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.repository.MusicRepositoryImpl
import net.asksakis.massdroidv2.data.repository.PlayHistoryRepositoryImpl
import net.asksakis.massdroidv2.data.repository.PlayerRepositoryImpl
import net.asksakis.massdroidv2.data.repository.SettingsRepositoryImpl
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository = SettingsRepositoryImpl(context)

    @Provides
    @Singleton
    fun providePlayHistoryRepository(
        dao: PlayHistoryDao
    ): PlayHistoryRepository = PlayHistoryRepositoryImpl(dao)

    @Provides
    @Singleton
    fun providePlayerRepository(
        wsClient: MaWebSocketClient,
        json: Json,
        playHistoryRepository: PlayHistoryRepository,
        settingsRepository: SettingsRepository
    ): PlayerRepository = PlayerRepositoryImpl(wsClient, json, playHistoryRepository, settingsRepository)

    @Provides
    @Singleton
    fun provideMusicRepository(
        wsClient: MaWebSocketClient,
        json: Json
    ): MusicRepository = MusicRepositoryImpl(wsClient, json)
}
