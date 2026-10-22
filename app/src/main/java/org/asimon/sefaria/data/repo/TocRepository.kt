package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.data.local.BookName
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.local.TocNode
import org.asimon.sefaria.data.local.dto
import org.asimon.sefaria.data.local.importToc
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.TocNodeDto
import java.io.File

/** One level of the category tree: [node] (null at the top), its [children], and every book under each child. */
data class TocLevel(val node: TocNodeDto?, val children: List<TocNodeDto>, val booksUnder: Map<String, List<String>>) {
    /** Every book under this level. */
    val books: List<String> get() = children.flatMap { booksUnder[key(it)].orEmpty() }

    companion object {
        /** A child's key in [booksUnder]: "b:<title>" for a book, "c:<en>" for a category. */
        fun key(n: TocNodeDto) = if (n.isBook) "b:" + n.title else "c:" + n.en
    }
}

/**
 * The library's category tree. It is kept in the toc_node table (not the response cache) so it is
 * there offline and after the cache is cleared; when the server's build changes it is downloaded
 * again as a SQLite file (/v1/toc.sqlite) into [tmpDir] and copied into the table as is. Screens
 * read the levels they show ([level]); nothing keeps the whole tree in memory.
 */
class TocRepository(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val db: SefariaDb,
    private val tmpDir: File,
) {
    private val dao = db.toc()
    private val tocLock = Mutex()

    /** The whole category tree, read from the table (after [syncToc]); built for each call, not kept. */
    suspend fun toc(): Load<TocDto> = syncToc().map { checkNotNull(dao.toc()) }

    /**
     * One level of the tree: the category at [path] (English names from the top; empty: the top
     * level), its children, and the books under each child.
     */
    suspend fun level(path: List<String>): Load<TocLevel> {
        val r = syncToc()
        if (r is Load.Err) return r
        val offline = (r as Load.Ok).offline
        var node: TocNode? = null
        for (name in path) node = dao.tocCategory(node?.id, name)
            ?: return Load.Ok(TocLevel(null, emptyList(), emptyMap()), offline) // not in this build's tree
        val children = dao.tocChildren(node?.id)
        val ids = children.associate { it.id to TocLevel.key(it.dto()) }
        val under = dao.booksUnder(node?.id).groupBy({ ids.getValue(it.root) }, { it.title })
        return Load.Ok(TocLevel(node?.dto(), children.map { it.dto() }, under), offline)
    }

    /** Every book's names (TOC order), for offline completion; empty if no tree is stored. */
    suspend fun bookNames(): List<BookName> = dao.bookNames()

    @Volatile private var tocBuild: String? = null // the build the stored tree is from, once read

    /**
     * Brings the stored tree up to the server's build: kept while the build is the same, else
     * downloaded again. Ok with the build of the tree in the table ([Load.Ok.offline] when the
     * server couldn't be asked); Err when there is no tree.
     */
    suspend fun syncToc(): Load<String> = tocLock.withLock {
        val build = fetcher.build
        val have = tocBuild ?: dao.build()?.also { tocBuild = it }
        if (have != null && (build == null || have == build)) return@withLock Load.Ok(have)
        tmpDir.mkdirs()
        val file = File(tmpDir, "toc.sqlite")
        try {
            when (val r = api.tocDb(file, have?.let { "\"$it\"" })) {
                is ApiResult.Ok -> try {
                    Load.Ok(db.importToc(file).also { tocBuild = it })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (have != null) Load.Ok(have) else Load.Err(AppError.BadResponse("toc.sqlite: ${e.message}"))
                }
                ApiResult.NotModified -> {
                    // the same tree in the new build
                    if (build == null) Load.Ok(have!!)
                    else Load.Ok(build.also { dao.stampBuild(it); tocBuild = it })
                }
                is ApiResult.Failure ->
                    if (have != null && r.error !is ApiError.Http) Load.Ok(have, offline = true)
                    else Load.Err(r.error.toAppError())
            }
        } finally {
            file.delete()
        }
    }
}
