package com.dflixtv.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A title as it appears in rows, grids and search results. */
@Serializable
data class TitleSummary(
    val id: Long,
    val kind: String? = null,
    val title: String = "",
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val rating: Double? = null,
    val genres: String? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val quality: String? = null,
    val trailerKey: String? = null,
) {
    val isShow: Boolean get() = kind == "tv"
    val posterUrl: String? get() = DflixApi.imageUrl("poster", posterPath)
    val backdropUrl: String? get() = DflixApi.imageUrl("backdrop", backdropPath)
    val genreList: List<String> get() = parseGenres(genres)
    val cleanOverview: String? get() = overview?.takeUnless { it.startsWith("$") }
}

@Serializable
data class HomeRow(val label: String, val titles: List<TitleSummary>)

@Serializable
data class SearchResponse(val results: List<TitleSummary> = emptyList())

@Serializable
data class BrowseResponse(
    val items: List<TitleSummary> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
)

/** One playable file: a movie version or a single episode. */
@Serializable
data class MediaFile(
    val id: Long,
    val titleId: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val path: String? = null,
    val size: Long? = null,
    val container: String? = null,
    val quality: String? = null,
    val codec: String? = null,
    val episodeOverview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
) {
    val stillUrl: String? get() = DflixApi.imageUrl("still", stillPath)
    val fileName: String? get() = path?.substringAfterLast('/')

    /** IMDb id embedded in the library path, e.g. "[imdb-tt0096895]". */
    val imdbId: String? get() = path?.let { IMDB_REGEX.find(it)?.groupValues?.get(1) }

    val episodeCode: String?
        get() = if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null

    companion object {
        private val IMDB_REGEX = Regex("""imdb-(tt\d+)""")
    }
}

@Serializable
data class TitleDetails(
    val id: Long,
    val kind: String? = null,
    val title: String = "",
    val year: Int? = null,
    val overview: String? = null,
    val tmdbId: Long? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val rating: Double? = null,
    val genres: String? = null,
    val path: String? = null,
    val runtime: Int? = null,
    val tagline: String? = null,
    val certification: String? = null,
    val originalLanguage: String? = null,
    /** YouTube video id of the trailer. */
    val trailerKey: String? = null,
    val files: List<MediaFile> = emptyList(),
) {
    val isShow: Boolean get() = kind == "tv"
    val posterUrl: String? get() = DflixApi.imageUrl("poster", posterPath)
    val backdropUrl: String? get() = DflixApi.imageUrl("backdrop", backdropPath)
    val genreList: List<String> get() = parseGenres(genres)
    val imdbId: String? get() = path?.let { Regex("""imdb-(tt\d+)""").find(it)?.groupValues?.get(1) }

    fun toSummary() = TitleSummary(
        id = id, kind = kind, title = title, year = year, posterPath = posterPath,
        backdropPath = backdropPath, rating = rating, genres = genres, runtime = runtime, overview = overview,
    )

    /** Episodes grouped by season, in order. Movies end up under a single null key. */
    val seasons: Map<Int?, List<MediaFile>>
        get() = files.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .groupBy { it.season }
}

@Serializable
data class SubtitleTrack(
    val id: Long,
    val src: String,
    val lang: String? = null,
    val label: String? = null,
)

@Serializable
data class NextEpisode(
    val href: String,
    val heading: String? = null,
    val subheading: String? = null,
) {
    /** "/watch/14349?e=134872" -> 134872 */
    val fileId: Long? get() = Regex("""[?&]e=(\d+)""").find(href)?.groupValues?.get(1)?.toLongOrNull()
}

/** Player props embedded in the /watch page. */
@Serializable
data class WatchInfo(
    val titleId: Long,
    val titleName: String = "",
    val fileId: Long,
    val fileExt: String? = null,
    val poster: String? = null,
    val transcodeMode: String? = null,
    val nextEpisode: NextEpisode? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val resumeAt: Double? = null,
)

private val genreJson = Json { ignoreUnknownKeys = true }

private fun parseGenres(raw: String?): List<String> =
    runCatching { genreJson.decodeFromString<List<String>>(raw ?: return emptyList()) }.getOrDefault(emptyList())
