package com.dflixtv.app.subs

import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

private val json = DflixApi.json

private suspend fun fetch(request: Request): ByteArray = withContext(Dispatchers.IO) {
    DflixApp.http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body.string().take(200)}")
        response.body.bytes()
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/**
 * OpenSubtitles.org legacy REST API. Needs no API key, and supports matching by file hash.
 * Path parameters must be in alphabetical order.
 */
class OpenSubtitlesOrg : SubtitleProvider {
    override val name = "OpenSubtitles.org"
    override val isConfigured = true

    override suspend fun search(query: SubtitleQuery): List<SubtitleResult> {
        val lang = "sublanguageid-${query.language.code3}"
        val searches = buildList {
            if (query.fileSize != null) {
                OsHash.compute(query.streamUrl, query.fileSize)?.let { hash ->
                    add("moviebytesize-${query.fileSize}/moviehash-$hash/$lang")
                }
            }
            val imdb = query.imdbId?.removePrefix("tt")
            when {
                query.isEpisode && imdb != null ->
                    add("episode-${query.episode}/imdbid-$imdb/season-${query.season}/$lang")
                query.isEpisode ->
                    add("episode-${query.episode}/query-${encode(query.title)}/season-${query.season}/$lang")
                imdb != null -> add("imdbid-$imdb/$lang")
                else -> add("query-${encode(query.title)}/$lang")
            }
        }
        val results = mutableListOf<SubtitleResult>()
        for (path in searches) {
            val items = runCatching { request(path) }.getOrDefault(emptyList())
            results += items.mapNotNull { parse(it, query) }
        }
        return results.distinctBy { it.downloadRef }
    }

    private suspend fun request(path: String): List<JsonObject> {
        val bytes = fetch(
            Request.Builder().url("https://rest.opensubtitles.org/search/$path")
                .header("User-Agent", "TemporaryUserAgent").build()
        )
        return json.parseToJsonElement(bytes.decodeToString()).jsonArray.mapNotNull { it.obj() }
    }

    private fun parse(item: JsonObject, query: SubtitleQuery): SubtitleResult? {
        val format = item.str("SubFormat")?.lowercase() ?: return null
        if (format !in SubtitleFormats.SUPPORTED) return null
        val link = item.str("SubDownloadLink") ?: return null
        if (query.isEpisode && !query.matchesTitle(item.str("MovieName"))) return null
        return SubtitleResult(
            provider = name,
            fileName = item.str("SubFileName") ?: "subtitle.$format",
            languageCode = item.str("ISO639") ?: query.language.code2,
            downloads = item.int("SubDownloadsCnt"),
            hearingImpaired = item.str("SubHearingImpaired") == "1",
            hashMatch = item.str("MatchedBy") == "moviehash",
            format = format,
            downloadRef = link,
            encodingHint = item.str("SubEncoding"),
        )
    }

    override suspend fun download(result: SubtitleResult, query: SubtitleQuery): Pair<String, ByteArray> {
        val gz = fetch(Request.Builder().url(result.downloadRef).header("User-Agent", "TemporaryUserAgent").build())
        return result.fileName to GZIPInputStream(gz.inputStream()).use { it.readBytes() }
    }

    private fun encode(text: String) = URLEncoder.encode(text.lowercase(), "UTF-8").replace("+", "%20")
}

/** OpenSubtitles.com REST API v1. Requires a free API key from opensubtitles.com/consumers. */
class OpenSubtitlesCom(private val store: Store) : SubtitleProvider {
    override val name = "OpenSubtitles.com"
    override val isConfigured get() = store.openSubtitlesApiKey.isNotBlank()

    private fun Request.Builder.auth() = header("Api-Key", store.openSubtitlesApiKey)
        .header("User-Agent", "DflixTV v1.0")
        .header("Accept", "application/json")

    override suspend fun search(query: SubtitleQuery): List<SubtitleResult> {
        val url = "https://api.opensubtitles.com/api/v1/subtitles".toHttpUrl().newBuilder().apply {
            addQueryParameter("languages", query.language.code2)
            if (query.isEpisode) {
                if (query.tmdbId != null) addQueryParameter("parent_tmdb_id", query.tmdbId.toString())
                else addQueryParameter("query", query.title)
                addQueryParameter("season_number", query.season.toString())
                addQueryParameter("episode_number", query.episode.toString())
            } else when {
                query.imdbId != null -> addQueryParameter("imdb_id", query.imdbId.removePrefix("tt"))
                query.tmdbId != null -> addQueryParameter("tmdb_id", query.tmdbId.toString())
                else -> addQueryParameter("query", query.title)
            }
        }.build()
        val body = json.parseToJsonElement(fetch(Request.Builder().url(url).auth().build()).decodeToString())
        return body.jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val attrs = element.obj()?.get("attributes").obj() ?: return@mapNotNull null
            val file = attrs["files"]?.jsonArray?.firstOrNull().obj() ?: return@mapNotNull null
            val fileId = file.str("file_id") ?: return@mapNotNull null
            val fileName = file.str("file_name") ?: attrs.str("release") ?: "subtitle.srt"
            SubtitleResult(
                provider = name,
                fileName = if ('.' in fileName.takeLast(5)) fileName else "$fileName.srt",
                languageCode = attrs.str("language") ?: query.language.code2,
                downloads = attrs.int("download_count"),
                hearingImpaired = attrs["hearing_impaired"]?.jsonPrimitive?.contentOrNull == "true",
                hashMatch = attrs["moviehash_match"]?.jsonPrimitive?.contentOrNull == "true",
                format = "srt",
                downloadRef = fileId,
            )
        }
    }

    override suspend fun download(result: SubtitleResult, query: SubtitleQuery): Pair<String, ByteArray> {
        val payload = buildJsonObject { put("file_id", result.downloadRef.toLong()) }.toString()
        val response = fetch(
            Request.Builder().url("https://api.opensubtitles.com/api/v1/download").auth()
                .post(payload.toRequestBody("application/json".toMediaType())).build()
        )
        val obj = json.parseToJsonElement(response.decodeToString()).jsonObject
        val link = obj.str("link") ?: throw IOException(obj.str("message") ?: "Download limit reached")
        return (obj.str("file_name") ?: result.fileName) to fetch(Request.Builder().url(link).build())
    }
}

/** SubDL API. Requires a free API key from subdl.com/panel/api. */
class SubDl(private val store: Store) : SubtitleProvider {
    override val name = "SubDL"
    override val isConfigured get() = store.subdlApiKey.isNotBlank()

    override suspend fun search(query: SubtitleQuery): List<SubtitleResult> {
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", store.subdlApiKey)
            addQueryParameter("languages", query.language.code2.substringBefore('-').uppercase())
            addQueryParameter("subs_per_page", "30")
            addQueryParameter("type", if (query.isEpisode) "tv" else "movie")
            when {
                query.tmdbId != null -> addQueryParameter("tmdb_id", query.tmdbId.toString())
                query.imdbId != null -> addQueryParameter("imdb_id", query.imdbId)
                else -> addQueryParameter("film_name", query.title)
            }
            if (query.isEpisode) {
                addQueryParameter("season_number", query.season.toString())
                addQueryParameter("episode_number", query.episode.toString())
            }
        }.build()
        val body = json.parseToJsonElement(fetch(Request.Builder().url(url).build()).decodeToString()).jsonObject
        return body["subtitles"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.obj() ?: return@mapNotNull null
            val path = item.str("url") ?: return@mapNotNull null
            SubtitleResult(
                provider = name,
                fileName = item.str("release_name") ?: item.str("name") ?: "subtitle",
                languageCode = item.str("lang") ?: query.language.code2,
                downloads = 0,
                hearingImpaired = item["hi"]?.jsonPrimitive?.contentOrNull == "true",
                hashMatch = false,
                format = "zip",
                downloadRef = "https://dl.subdl.com$path",
            )
        }
    }

    override suspend fun download(result: SubtitleResult, query: SubtitleQuery): Pair<String, ByteArray> {
        val zip = fetch(Request.Builder().url(result.downloadRef).build())
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val ext = entry.name.substringAfterLast('.').lowercase()
                if (!entry.isDirectory && ext in SubtitleFormats.SUPPORTED) {
                    entries += entry.name.substringAfterLast('/') to input.readBytes()
                }
            }
        }
        // Season packs contain many files; pick the one for this episode when possible.
        val code = query.episodeCodeRegex()
        return entries.firstOrNull { code != null && code.containsMatchIn(it.first) }
            ?: entries.firstOrNull()
            ?: throw IOException("No subtitle file in archive")
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun SubtitleQuery.episodeCodeRegex(): Regex? =
    if (isEpisode) Regex("""(?i)s0?${season}e0?${episode}(?!\d)|\b${season}x0?${episode}\b""") else null

private fun SubtitleQuery.matchesTitle(movieName: String?): Boolean {
    if (movieName == null) return true
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    val wanted = norm(title)
    // Episode names look like "\"Breaking Bad\" Pilot"; compare the quoted show name.
    val show = Regex("^\"([^\"]+)\"").find(movieName)?.groupValues?.get(1) ?: movieName
    return norm(show) == wanted || norm(show).startsWith(wanted)
}
