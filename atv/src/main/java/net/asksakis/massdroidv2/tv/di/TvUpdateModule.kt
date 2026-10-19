package net.asksakis.massdroidv2.tv.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.asksakis.massdroidv2.data.update.UpdateAssetName

@Module
@InstallIn(SingletonComponent::class)
object TvUpdateModule {
    /** The Android TV APK of a release, named as release.yml publishes it. */
    @Provides
    fun updateAssetName(): UpdateAssetName = UpdateAssetName { version -> "massdroid-tv-v$version.apk" }
}
