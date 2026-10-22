package org.asimon.sefaria.data.local

import java.io.File

/**
 * The downloaded library, kept as downloaded in [dir] (SERVER_SPEC §7): its files and rows
 * ([files]), reads ([reader]) and installs and deletes ([installer]).
 */
class Library(dir: File, dao: InstalledDao) {
    val files = LibraryFiles(dir, dao)
    val reader = LibraryReader(files)
    val installer = LibraryInstaller(files, reader)
}
