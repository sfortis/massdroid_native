package net.asksakis.massdroidv2.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.AppDatabase
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.sendspin.AudioStreamManager
import net.asksakis.massdroidv2.data.sendspin.SendspinClient
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideMaWebSocketClient(
        okHttpClient: OkHttpClient,
        json: Json
    ): MaWebSocketClient = MaWebSocketClient(okHttpClient, json)

    @Provides
    @Singleton
    fun provideSendspinClient(
        wsClient: MaWebSocketClient,
        json: Json
    ): SendspinClient = SendspinClient(
        httpClientProvider = { wsClient.getHttpClient() },
        json = json
    )

    @Provides
    @Singleton
    fun provideAudioStreamManager(): AudioStreamManager = AudioStreamManager()

    @Provides
    @Singleton
    fun provideSendspinManager(
        client: SendspinClient,
        audio: AudioStreamManager,
    ): SendspinManager = SendspinManager(client, audio)

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "massdroid.db"
    ).fallbackToDestructiveMigration().build()

    @Provides
    fun providePlayHistoryDao(db: AppDatabase): PlayHistoryDao = db.playHistoryDao()
}
