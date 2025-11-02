package org.asimon.sefaria.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.cache.CacheDataSource
import org.asimon.sefaria.data.cache.InMemoryCacheDataSource
import org.asimon.sefaria.data.cache.PersistentCacheDataSource
import org.asimon.sefaria.data.demo.MockNetworkDataSource
import org.asimon.sefaria.data.network.NetworkDataSource
import org.asimon.sefaria.data.network.OkHttpNetworkDataSource
import org.asimon.sefaria.data.repositories.DataRepository
import org.asimon.sefaria.data.repositories.NetworkCacheDataRepository
import org.asimon.sefaria.data.repositories.NetworkOnlyDataRepository
import javax.inject.Named
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindDataRepository(impl: NetworkCacheDataRepository): DataRepository

    @Binds
    abstract fun bindNetworkDataSource(impl: OkHttpNetworkDataSource): NetworkDataSource

    @Binds
    abstract fun bindCacheDataSource(impl: PersistentCacheDataSource): CacheDataSource

    companion object {
        @Provides
        @Singleton // Ensure a single instance is provided
        fun provideOkHttpClient(): OkHttpClient {
            return OkHttpClient()
        }

        @Provides
        @Singleton // Ensure a single instance is provided
        fun provideIoDispatcher(): CoroutineDispatcher {
            return Dispatchers.IO
        }

        @Provides
        @Named("cacheMaxSize")
        fun provideCacheMaxSize(): Int {
            return 1_000_000
        }
    }
}