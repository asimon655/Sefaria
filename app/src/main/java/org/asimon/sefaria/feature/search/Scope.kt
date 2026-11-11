package org.asimon.sefaria.feature.search

import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.core.debugOnly
import org.asimon.sefaria.data.remote.TocNodeDto

/**
 * The TOC as the scope picker sees it: every category by its path ("Tanakh/Torah"), every book
 * with the path of its category. Books may appear under more than one category; a book's path
 * is the first.
 */
class ScopeTree(val roots: List<TocNodeDto>) {
    /** A category or a book; [path] is the category's own path, or the book's category path. */
    data class Entry(val node: TocNodeDto, val path: String, val depth: Int, val trail: List<TocNodeDto>) {
        val isBook get() = node.isBook
        val key get() = if (isBook) "b:" + node.title else "c:$path"
    }

    val entries: List<Entry>
    private val booksUnder = HashMap<String, MutableSet<String>>() // category path -> book titles
    private val bookPath = HashMap<String, String>()

    init {
        val out = ArrayList<Entry>()
        fun walk(nodes: List<TocNodeDto>, depth: Int, path: String, trail: List<TocNodeDto>) {
            for (n in nodes) {
                if (n.isBook) {
                    out += Entry(n, path, depth, trail)
                    bookPath.putIfAbsent(n.title, path)
                    var p = path
                    while (p.isNotEmpty()) {
                        booksUnder.getOrPut(p) { HashSet() } += n.title
                        p = p.substringBeforeLast('/', "")
                    }
                } else {
                    val p = if (path.isEmpty()) n.en else "$path/${n.en}"
                    out += Entry(n, p, depth, trail)
                    walk(n.children, depth + 1, p, trail + n)
                }
            }
        }
        walk(roots, 0, "", emptyList())
        entries = out
    }

    /** How many books a category holds. */
    fun count(path: String): Int = booksUnder[path]?.size ?: 0

    /** The scope after a category's box is ticked or cleared: ticking it replaces what it covers. */
    fun setCategory(s: Scope, path: String, on: Boolean): Scope = if (on) {
        val under = booksUnder[path].orEmpty()
        Scope(s.books - under, s.categories.filterNot { it.startsWith("$path/") }.toSet() + path)
    } else s.copy(categories = s.categories - path)

    fun setBook(s: Scope, title: String, on: Boolean): Scope = s.copy(books = if (on) s.books + title else s.books - title)

    /** A category above [path] (or a book's category) is chosen: its box shows ticked, locked. */
    fun covered(s: Scope, path: String, isBook: Boolean): Boolean =
        s.categories.any { c -> (isBook && path == c) || path.startsWith("$c/") }

    /** Something under a category is chosen, but not the category itself: its box shows "partly". */
    fun partly(s: Scope, path: String): Boolean =
        path !in s.categories && (s.categories.any { it.startsWith("$path/") } || s.books.any { it in booksUnder[path].orEmpty() })

    /**
     * The same scope in as few names as it takes: a category whose every book is chosen stands
     * for them, and when that leaves every top category chosen, the whole library does. The
     * scope goes to the server as a name per choice, so "the downloaded books" of a whole
     * library downloaded was a request of six thousand titles; this makes it none.
     */
    fun compact(s: Scope): Scope {
        if (s.books.isEmpty()) return s
        val chosen = s.books + s.categories.flatMap { booksUnder[it].orEmpty() }
        var out = s
        for (e in entries) { // parents come before their children
            if (e.isBook || e.path in out.categories || covered(out, e.path, false)) continue
            val under = booksUnder[e.path].orEmpty()
            if (under.isNotEmpty() && chosen.containsAll(under)) out = setCategory(out, e.path, true)
        }
        val tops = roots.filter { !it.isBook }
        val whole = out.books.isEmpty() && tops.isNotEmpty() && tops.all { it.en in out.categories } && roots.none { it.isBook }
        debugOnly {
            val before = chosen
            val after = out.books + out.categories.flatMap { booksUnder[it].orEmpty() }
            debugAssert(before == after) { "compacting changed the scope: ${before.size} books became ${after.size}" }
        }
        return if (whole) Scope() else out
    }

    /** The category path a book sits in (its first, when it's in several). */
    fun pathOf(title: String): String? = bookPath[title]
}

/** What a search covers: the union of [books] and [categories]; both empty is the whole library. */
data class Scope(val books: Set<String> = emptySet(), val categories: Set<String> = emptySet()) {
    val isAll get() = books.isEmpty() && categories.isEmpty()

    /**
     * The scope as one string, for a route or saved state: a line per choice, "b:" before a book
     * and "c:" before a category path. "The [downloaded] books" is the single line "d" however
     * many they are, so thousands of titles don't travel through navigation.
     */
    fun encode(downloaded: Set<String>): String = when {
        isAll -> ""
        categories.isEmpty() && books.size > 1 && books == downloaded -> DOWNLOADED
        else -> (categories.sorted().map { "c:$it" } + books.sorted().map { "b:$it" }).joinToString("\n")
    }

    companion object {
        private const val DOWNLOADED = "d"

        /** Whether [encoded] names the downloaded books, which [decode] then has to be given. */
        fun needsDownloaded(encoded: String) = encoded == DOWNLOADED

        /** The scope [encode] wrote; lines it doesn't know are skipped. */
        fun decode(encoded: String, downloaded: Set<String>): Scope {
            if (encoded == DOWNLOADED) return Scope(books = downloaded)
            val books = HashSet<String>()
            val categories = HashSet<String>()
            for (line in encoded.split('\n')) when {
                line.startsWith("b:") -> books += line.substring(2)
                line.startsWith("c:") -> categories += line.substring(2)
            }
            debugAssert(encoded.isEmpty() || books.size + categories.size > 0) { "scope \"$encoded\" decodes to nothing" }
            return Scope(books, categories)
        }
    }
}
