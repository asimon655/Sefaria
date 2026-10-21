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
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.asimon.sefaria.BuildConfig
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.data.local.CacheDb
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
import org.asimon.sefaria.work.DownloadWorker
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
            // before the first release the schema changes freely: a new version starts empty
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    /** In the cache folder, so "Clear cache" in the app's info empties it. */
    @Provides @Singleton
    fun cacheDb(@ApplicationContext ctx: Context): CacheDb =
        Room.databaseBuilder(ctx, CacheDb::class.java, File(ctx.cacheDir, CacheDb.FILE).path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * How requests are sent. Debug builds speak HTTP/2 to a plain-HTTP server too (h2c with prior
     * knowledge; the dev server accepts it next to HTTP/1.1), so a local server is tested over
     * the protocol production uses. HTTPS always negotiates in the TLS handshake.
     */
    @Provides @Singleton
    fun calls(client: OkHttpClient): Call.Factory = if (BuildConfig.H2C) h2c(client) else client

    /** [client] for https URLs; for http ones, the same client (and pool) speaking h2c. */
    fun h2c(client: OkHttpClient): Call.Factory {
        val plain = client.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
        return Call.Factory { r -> (if (r.url.isHttps) client else plain).newCall(r) }
    }

    @Provides @Singleton
    fun api(client: Call.Factory, settings: Settings) = Api(client, settings::serverUrl)

    @Provides @Singleton
    fun buildInfo() = BuildInfo()

    @Provides @Singleton
    fun libraryStore(@ApplicationContext ctx: Context, db: SefariaDb) = LibraryStore(File(ctx.filesDir, "library"), db.library())

    @Provides @Singleton
    fun offline(store: LibraryStore) = OfflineLibrary(store)

    @Provides @Singleton
    fun fetcher(api: Api, cache: CacheDb, build: BuildInfo, store: LibraryStore) = CachedFetcher(api, cache.cache(), build, store)

    @Provides @Singleton
    fun downloads(
        @ApplicationContext ctx: Context, api: Api, fetcher: CachedFetcher, db: SefariaDb, store: LibraryStore, client: Call.Factory,
        settings: Settings, scope: CoroutineScope,
    ) = DownloadRepository(
        api, fetcher, db.library(), store, FileDownloader(client, settings::serverUrl), scope, File(ctx.filesDir, "downloads.pending"),
        onQueued = { DownloadWorker.start(ctx) },
    )

    @Provides @Singleton
    fun library(api: Api, fetcher: CachedFetcher, db: SefariaDb) = LibraryRepository(api, fetcher, db.library())

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
