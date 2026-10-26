package com.dflixtv.app

import com.dflixtv.app.data.AiSuggest
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.Rsc
import com.dflixtv.app.subs.Language
import com.dflixtv.app.subs.SubtitleAds
import com.dflixtv.app.subs.SubtitleQuery
import com.dflixtv.app.subs.SubtitleRanking
import com.dflixtv.app.subs.SubtitleResult
import com.dflixtv.app.subs.SubtitleShifter
import com.dflixtv.app.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts most likely to break when dflix.live or a subtitle site changes its output. */
class ParsingTest {

    // region dflix.live page payloads

    private val rscPage = """
        0:["${'$'}","div",null,{"children":"x"}]
        1:["${'$'}","section",null,{"label":"Trending Now","titles":[{"id":11,"title":"Dark","year":2017,"kind":"tv","posterPath":"/d.jpg"},{"id":12,"title":"Arrival","year":2016,"kind":"movie"}]}]
        2:{"label":"Recently Added","titles":[{"id":13,"title":"Brace \"}\" yourself","extra":{"nested":[1,2,{"a":"]"}]}}]}
        3:{"label":"Empty","titles":[]}
        4:{"titleId":99,"titleName":"Show","fileId":5,"subtitles":[]}
    """.trimIndent()

    @Test
    fun homeRowsAreParsedFromThePagePayload() {
        val rows = DflixApi.parseRows(rscPage)
        assertEquals(listOf("Trending Now", "Recently Added"), rows.map { it.label })
        assertEquals(listOf(11L, 12L), rows[0].titles.map { it.id })
        assertTrue(rows[0].titles[0].isShow)
        // Braces and brackets inside strings don't end the value early.
        assertEquals("Brace \"}\" yourself", rows[1].titles.single().title)
    }

    @Test
    fun objectStartingWithFindsTheWholeObject() {
        val obj = Rsc.objectStartingWith(rscPage, "{\"titleId\":99,\"titleName\":")
        assertEquals("""{"titleId":99,"titleName":"Show","fileId":5,"subtitles":[]}""", obj)
        assertNull(Rsc.objectStartingWith(rscPage, "{\"titleId\":100,"))
    }

    // endregion

    // region Subtitles

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello there.

        2
        00:00:03,000 --> 00:00:04,000
        Watch Online Movies and Series for FREE
        www.osdb.link/lm

        3
        00:59:59,900 --> 01:00:00,400
        Goodbye.
    """.trimIndent()

    @Test
    fun shiftMovesEveryTimestampAndNeverGoesNegative() {
        val later = SubtitleShifter.shift(srt, 1_500, "srt")
        assertTrue(later.contains("00:00:02,500 --> 00:00:04,000"))
        assertTrue(later.contains("01:00:01,400 --> 01:00:01,900"))
        val earlier = SubtitleShifter.shift(srt, -2_000, "srt")
        assertTrue(earlier.contains("00:00:00,000 --> 00:00:00,500"))
    }

    @Test
    fun advertCuesAreRemovedAndDialogueKept() {
        val clean = SubtitleAds.strip(srt, "srt")
        assertFalse(clean.contains("osdb.link"))
        assertTrue(clean.contains("Hello there."))
        assertTrue(clean.contains("Goodbye."))
    }

    private val query = SubtitleQuery(
        title = "Interstellar",
        year = 2014,
        imdbId = "tt0816692",
        tmdbId = null,
        season = null,
        episode = null,
        language = Language("eng", "en", "English"),
        fileName = "Interstellar.2014.1080p.BluRay.x264-SPARKS.mkv",
        fileSize = null,
        streamUrl = "",
        durationMs = 169 * 60_000L,
    )

    private fun result(name: String, lastCueMs: Long? = null, downloads: Int = 100, hash: Boolean = false) = SubtitleResult(
        provider = "test", fileName = name, languageCode = "eng", downloads = downloads, hearingImpaired = false,
        hashMatch = hash, format = "srt", downloadRef = "", lastCueMs = lastCueMs,
    )

    @Test
    fun subtitlesForAShorterFilmAreRejected() {
        assertFalse(SubtitleRanking.fitsDuration(result("other.srt", lastCueMs = 60 * 60_000L), query))
        assertTrue(SubtitleRanking.fitsDuration(result("ok.srt", lastCueMs = 165 * 60_000L), query))
        assertTrue(SubtitleRanking.fitsDuration(result("unknown.srt"), query))
    }

    @Test
    fun matchingReleaseNameRanksAboveMorePopular() {
        val sameRelease = result("Interstellar.2014.1080p.BluRay.x264-SPARKS.srt", downloads = 10)
        val popular = result("Interstellar.2014.720p.WEB-DL.srt", downloads = 50_000)
        assertTrue(SubtitleRanking.score(sameRelease, query) > SubtitleRanking.score(popular, query))
    }

    // endregion

    // region AI suggestions

    @Test
    fun aiTitlesAreParsedFromCommonAnswerFormats() {
        val parsed = AiSuggest.parseTitles(
            listOf(
                "1. Prisoners (2013)",
                "- **Dark** (2017) - German series",
                "Spider-Man: No Way Home (2021)",
                "Mr. Robot (2015–2019)",
                "Se7en",
                "1. Prisoners (2013)",
                "",
            )
        )
        assertEquals(
            listOf("Prisoners" to 2013, "Dark" to 2017, "Spider-Man: No Way Home" to 2021, "Mr. Robot" to 2015, "Se7en" to null),
            parsed,
        )
    }

    @Test
    fun referencedTitleIsTheOneAfterLike() {
        assertEquals("Interstellar", AiSuggest.referencedTitle("space movies like Interstellar"))
        assertEquals("Dark", AiSuggest.referencedTitle("something similar to Dark"))
        assertNull(AiSuggest.referencedTitle("funny korean drama"))
        assertEquals(AiSuggest.normalize("The Martian"), AiSuggest.normalize("the martian!"))
    }

    // endregion

    @Test
    fun releaseNotesAreUnwrappedWithinParagraphs() {
        val notes = "First line of a\nwrapped paragraph.\n\nSecond\nparagraph."
        assertEquals("First line of a wrapped paragraph.\n\nSecond paragraph.", Updater.unwrap(notes))
    }
}
