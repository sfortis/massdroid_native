package net.asksakis.massdroidv2.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.asksakis.massdroidv2.data.update.UpdateAssetName

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    /** The phone APK of a release, named as release.yml publishes it. */
    @Provides
    fun updateAssetName(): UpdateAssetName = UpdateAssetName { version -> "massdroid-v$version.apk" }
}
