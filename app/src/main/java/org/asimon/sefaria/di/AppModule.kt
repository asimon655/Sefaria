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
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.LibraryFiles
import org.asimon.sefaria.data.local.LibraryInstaller
import org.asimon.sefaria.data.local.LibraryReader
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.datasource.BuildInfo
import org.asimon.sefaria.data.datasource.CachedFetcher
import org.asimon.sefaria.data.repo.BookRepository
import org.asimon.sefaria.data.repo.CompletionRepository
import org.asimon.sefaria.data.repo.TocRepository
import org.asimon.sefaria.data.repo.LinksRepository
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.datasource.FileDownloader
import org.asimon.sefaria.data.repo.LibraryListing
import org.asimon.sefaria.data.repo.SharedFiles
import org.asimon.sefaria.data.datasource.OfflineLinks
import org.asimon.sefaria.data.datasource.OfflineSearch
import org.asimon.sefaria.data.datasource.OfflineText
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
            .addMigrations(SefariaDb.MIGRATION_1_2)
            // before the first release the schema may still change freely: a version with no migration starts empty
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
    fun downloadedLibrary(@ApplicationContext ctx: Context, db: SefariaDb) = Library(File(ctx.filesDir, "library"), db.installed())

    @Provides fun libraryFiles(l: Library): LibraryFiles = l.files

    @Provides fun libraryReader(l: Library): LibraryReader = l.reader

    @Provides fun libraryInstaller(l: Library): LibraryInstaller = l.installer

    @Provides @Singleton
    fun offlineLinks(reader: LibraryReader) = OfflineLinks(reader)

    @Provides @Singleton
    fun offlineText(reader: LibraryReader, links: OfflineLinks) = OfflineText(reader, links)

    @Provides @Singleton
    fun offlineSearch(reader: LibraryReader) = OfflineSearch(reader)

    @Provides @Singleton
    fun fetcher(api: Api, cache: CacheDb, build: BuildInfo, reader: LibraryReader) = CachedFetcher(api, cache.cache(), build, reader)

    @Provides @Singleton
    fun fileDownloader(client: Call.Factory, settings: Settings) = FileDownloader(client, settings::serverUrl)

    @Provides @Singleton
    fun listing(api: Api, fetcher: CachedFetcher, db: SefariaDb, scope: CoroutineScope) = LibraryListing(api, fetcher, db.listing(), scope)

    @Provides @Singleton
    fun sharedFiles(api: Api, listing: LibraryListing, library: Library, downloader: FileDownloader, scope: CoroutineScope) =
        SharedFiles(api, listing, library, downloader, scope)

    @Provides @Singleton
    fun downloads(
        @ApplicationContext ctx: Context, api: Api, listing: LibraryListing, shared: SharedFiles, library: Library,
        downloader: FileDownloader, scope: CoroutineScope,
    ) = DownloadRepository(
        api, listing, shared, library, downloader, scope,
        onQueued = { DownloadWorker.start(ctx) },
    )

    @Provides @Singleton
    fun toc(@ApplicationContext ctx: Context, api: Api, fetcher: CachedFetcher, db: SefariaDb) =
        TocRepository(api, fetcher, db, File(ctx.cacheDir, "toc"))

    @Provides @Singleton
    fun books(api: Api, fetcher: CachedFetcher) = BookRepository(api, fetcher)

    @Provides @Singleton
    fun completion(api: Api, toc: TocRepository) = CompletionRepository(api, toc)

    @Provides @Singleton
    fun text(api: Api, fetcher: CachedFetcher, offline: OfflineText) = TextRepository(api, fetcher, offline)

    @Provides @Singleton
    fun links(api: Api, fetcher: CachedFetcher, offline: OfflineLinks, books: BookRepository) =
        LinksRepository(api, fetcher, offline, books)

    @Provides @Singleton
    fun search(api: Api, books: BookRepository, files: LibraryFiles, reader: LibraryReader, db: SefariaDb, offline: OfflineSearch) =
        SearchRepository(api, books, files, reader, db.installed(), offline)

    @Provides @Singleton
    fun calendar(api: Api, fetcher: CachedFetcher) = CalendarRepository(api, fetcher)

    @Provides @Singleton
    fun userData(db: SefariaDb) = UserDataRepository(db.user())
}
