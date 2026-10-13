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
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.LinksRepository
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.repo.FileDownloader
import org.asimon.sefaria.data.repo.OfflineLibrary
import org.asimon.sefaria.data.repo.CalendarRepository
import org.asimon.sefaria.data.repo.SearchRepository
import org.asimon.sefaria.data.repo.TextRepository
import org.asimon.sefaria.data.repo.UserDataRepository
import java.io.File
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
    fun libraryStore(@ApplicationContext ctx: Context): LibraryStore {
        File(ctx.filesDir, "packs").deleteRecursively() // downloads of app versions before the library files
        return LibraryStore(File(ctx.filesDir, "library"))
    }

    @Provides @Singleton
    fun offline(store: LibraryStore) = OfflineLibrary(store)

    @Provides @Singleton
    fun fetcher(api: Api, db: SefariaDb, build: BuildInfo, store: LibraryStore) = CachedFetcher(api, db.cache(), build, store)

    @Provides @Singleton
    fun downloads(api: Api, fetcher: CachedFetcher, store: LibraryStore, client: OkHttpClient, settings: Settings, scope: CoroutineScope) =
        DownloadRepository(api, fetcher, store, FileDownloader(client, settings::serverUrl), scope)

    @Provides @Singleton
    fun library(api: Api, fetcher: CachedFetcher) = LibraryRepository(api, fetcher)

    @Provides @Singleton
    fun text(api: Api, fetcher: CachedFetcher, offline: OfflineLibrary) = TextRepository(api, fetcher, offline)

    @Provides @Singleton
    fun links(api: Api, fetcher: CachedFetcher, offline: OfflineLibrary, library: LibraryRepository) =
        LinksRepository(api, fetcher, offline, library)

    @Provides @Singleton
    fun search(api: Api, library: LibraryRepository, store: LibraryStore, offline: OfflineLibrary) =
        SearchRepository(api, library, store, offline)

    @Provides @Singleton
    fun calendar(api: Api, fetcher: CachedFetcher) = CalendarRepository(api, fetcher)

    @Provides @Singleton
    fun userData(db: SefariaDb) = UserDataRepository(db.user())
}
