package org.asimon.sefaria.data.local

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Which server build the stored copy of [what] ([LISTING], [TOC]) came from. */
@Entity(tableName = "synced")
data class Synced(@PrimaryKey val what: String, val build: String) {
    companion object {
        const val LISTING = "listing"
        const val TOC = "toc"
    }
}

/**
 * The library's state (files/databases/sefaria.db): the server's listing ([ListingDao]), the
 * downloaded files ([InstalledDao]) and the category tree ([TocDao]). The user's own data is in
 * [UserDb].
 */
@Database(
    entities = [ListedFile::class, InstalledFile::class, Synced::class, TocNode::class],
    version = 3,
)
abstract class SefariaDb : RoomDatabase() {
    abstract fun listing(): ListingDao
    abstract fun installed(): InstalledDao
    abstract fun toc(): TocDao

    companion object {
        /**
         * Version 2: the download queue moves from files/downloads.pending into listed_file.queued,
         * and toc_node gets indexes for walking the tree and joining on titles. A migration, not a
         * fresh start: dropping installed_file would make the next tidy delete every download.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `listed_file` ADD COLUMN `queued` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_toc_node_parent` ON `toc_node` (`parent`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_toc_node_title` ON `toc_node` (`title`)")
            }
        }

        /**
         * Version 3: the user's tables moved to [UserDb]. Their rows are not carried over (nothing
         * is released yet); this exists so that installed_file survives, as above.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                for (t in listOf("bookmark", "history", "reading_position")) connection.execSQL("DROP TABLE IF EXISTS `$t`")
            }
        }
    }
}
