package org.asimon.sefaria.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.asimon.sefaria.BuildConfig
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.TextRepository
import org.asimon.sefaria.data.repo.UserDataRepository
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun settings(@ApplicationContext ctx: Context, scope: CoroutineScope) =
        Settings(ctx.dataStore, scope, BuildConfig.API_BASE_URL)

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): SefariaDb =
        Room.databaseBuilder(ctx, SefariaDb::class.java, "sefaria.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun api(client: OkHttpClient, settings: Settings) = Api(client, settings::serverUrl)

    @Provides @Singleton
    fun buildInfo() = BuildInfo()

    @Provides @Singleton
    fun fetcher(api: Api, db: SefariaDb, build: BuildInfo) = CachedFetcher(api, db.cache(), build)

    @Provides @Singleton
    fun library(api: Api, fetcher: CachedFetcher) = LibraryRepository(api, fetcher)

    @Provides @Singleton
    fun text(api: Api, fetcher: CachedFetcher) = TextRepository(api, fetcher)

    @Provides @Singleton
    fun userData(db: SefariaDb) = UserDataRepository(db.user())
}
