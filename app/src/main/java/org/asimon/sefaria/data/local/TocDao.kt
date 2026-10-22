package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import org.asimon.sefaria.data.remote.Bilingual
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.TocNodeDto

/** A node of the library's category tree; [id] is its pre-order position, [parent] null at the top. */
@Entity(tableName = "toc_node", indices = [Index("parent"), Index("title")])
data class TocNode(
    @PrimaryKey val id: Int,
    val parent: Int?,
    val kind: String,
    val en: String,
    val he: String,
    val title: String,
    @ColumnInfo(name = "desc_en") val descEn: String,
    @ColumnInfo(name = "desc_he") val descHe: String,
    @ColumnInfo(name = "short_en") val shortEn: String,
    @ColumnInfo(name = "short_he") val shortHe: String,
    /** Books only: a commentary or targum, searched after the texts. */
    @ColumnInfo(defaultValue = "0") val dependent: Boolean = false,
)

/** A book of the TOC under the node [root] (a child of the level asked for). */
data class BookUnder(val root: Int, val title: String)

/** A book's names, for offline completion; [id] is its TOC position. */
data class BookName(val id: Int, val title: String, val he: String, val dependent: Boolean)

/** The library's category tree (toc_node), copied in whole from /v1/toc.sqlite (TocImport.kt) with its build. */
@Dao
abstract class TocDao {
    /** The stored tree, with the build it came from; null if none is stored. */
    @Transaction
    open suspend fun toc(): TocDto? {
        val build = build() ?: return null
        return TocDto(build, tocTree(tocNodes()))
    }

    @Query("SELECT * FROM toc_node ORDER BY id")
    protected abstract suspend fun tocNodes(): List<TocNode>

    /** The children of [parent] (null: the top level), in TOC order. */
    @Query("SELECT * FROM toc_node WHERE parent IS :parent ORDER BY id")
    abstract suspend fun tocChildren(parent: Int?): List<TocNode>

    /** The category named [en] under [parent] (null: the top level). */
    @Query("SELECT * FROM toc_node WHERE parent IS :parent AND kind = 'category' AND en = :en LIMIT 1")
    abstract suspend fun tocCategory(parent: Int?, en: String): TocNode?

    /** Every book under each child of [parent] (a book child: itself), in TOC order. */
    @Query(
        """WITH RECURSIVE sub(root, id) AS (
             SELECT id, id FROM toc_node WHERE parent IS :parent
             UNION ALL SELECT sub.root, n.id FROM toc_node n JOIN sub ON n.parent = sub.id)
           SELECT sub.root AS root, n.title AS title FROM sub JOIN toc_node n ON n.id = sub.id
           WHERE n.kind = 'book' ORDER BY n.id"""
    )
    abstract suspend fun booksUnder(parent: Int?): List<BookUnder>

    /** Every book's names, in TOC order. */
    @Query("SELECT id, title, he, dependent FROM toc_node WHERE kind = 'book' ORDER BY id")
    abstract suspend fun bookNames(): List<BookName>

    /** The build the stored tree came from; null if none is stored. */
    @Query("SELECT build FROM synced WHERE what = '${Synced.TOC}'")
    abstract suspend fun build(): String?

    /** Records that the stored tree is [build]'s (it didn't change in that build). */
    @Query("INSERT OR REPLACE INTO synced(what, build) VALUES('${Synced.TOC}', :build)")
    abstract suspend fun stampBuild(build: String)
}

/** One node without its children (a level of the tree is read on its own). */
fun TocNode.dto() = TocNodeDto(kind, en, he, title, Bilingual(descEn, descHe), Bilingual(shortEn, shortHe), emptyList(), dependent)

/** The tree back from its toc_node rows (in id order). */
fun tocTree(rows: List<TocNode>): List<TocNodeDto> {
    val children = rows.groupBy { it.parent }
    fun node(r: TocNode): TocNodeDto = TocNodeDto(
        r.kind, r.en, r.he, r.title, Bilingual(r.descEn, r.descHe), Bilingual(r.shortEn, r.shortHe),
        children[r.id].orEmpty().map(::node), r.dependent,
    )
    return children[null].orEmpty().map(::node)
}
