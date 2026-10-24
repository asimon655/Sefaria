package org.asimon.sefaria.data.repo

import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.CalendarDto
import org.asimon.sefaria.data.remote.SefariaJson
import java.time.LocalDate
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.datasource.CachedFetcher

/**
 * Today's learning (APP_SPEC §4.6). Days are cached like other responses, so a day fetched
 * once (Today prefetches the coming week) still shows offline.
 */
class CalendarRepository(private val api: Api, private val fetcher: CachedFetcher) {
    suspend fun day(date: LocalDate, diaspora: Boolean, sephardi: Boolean): Load<CalendarDto> {
        val d = date.toString()
        val custom = if (sephardi) "sephardi" else "ashkenazi"
        return fetcher.get(
            "calendar:$d|${if (diaspora) "diaspora" else "israel"}|$custom",
            { SefariaJson.decodeFromString(CalendarDto.serializer(), it) },
        ) { etag -> api.calendar(d, diaspora, custom, etag) }
    }
}
