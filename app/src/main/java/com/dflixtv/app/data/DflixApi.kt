package com.dflixtv.app.data

import com.dflixtv.app.DflixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * Client for dflix.live. JSON endpoints are used where they exist; title details, home rows and
 * player info only exist inside the page payload, so those are parsed from the RSC response.
 */
object DflixApi {
    const val BASE = "https://dflix.live"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client get() = DflixApp.http

    fun imageUrl(type: String, path: String?): String? =
        path?.takeIf { it.startsWith("/") }?.let { "$BASE/api/image/$type$it" }

    fun streamUrl(fileId: Long) = "$BASE/api/stream/$fileId"
    fun audioFixUrl(fileId: Long) = "$BASE/api/audio/$fileId"
    fun absolute(path: String) = if (path.startsWith("http")) path else BASE + path

    suspend fun search(query: String): List<TitleSummary> {
        val url = "$BASE/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "20")
            .build()
        return json.decodeFromString<SearchResponse>(get(url)).results
    }

    suspend fun browse(filter: BrowseFilter, page: Int): BrowseResponse {
        val url = "$BASE/api/browse".toHttpUrl().newBuilder().apply {
            addQueryParameter("kind", filter.kind)
            addQueryParameter("page", page.toString())
            if (filter.anime) addQueryParameter("anime", "1")
            filter.language?.let { addQueryParameter("language", it) }
            filter.genre?.let { addQueryParameter("genre", it) }
            addQueryParameter("sort", filter.sort)
            addQueryParameter("order", filter.order)
        }.build()
        return json.decodeFromString<BrowseResponse>(get(url))
    }

    suspend fun homeRows(): List<HomeRow> = parseRows(getRsc("$BASE/"))

    /** Title metadata, its files/episodes and the "More like this" rows from the title page. */
    suspend fun titlePage(titleId: Long): Pair<TitleDetails, List<HomeRow>> {
        val text = getRsc("$BASE/title/$titleId")
        val obj = Rsc.objectStartingWith(text, "{\"id\":$titleId,\"kind\":")
            ?.takeIf { it.contains("\"files\":[") }
            ?: Rsc.valueAfter(text, "\"title\":", text.indexOf("\"title\":{\"id\":$titleId"))
            ?: throw IOException("Title not found")
        return json.decodeFromString<TitleDetails>(obj) to parseRows(text)
    }

    internal fun parseRows(text: String): List<HomeRow> =
        Rsc.valuesAfter(text, ROW_REGEX).mapNotNull { (m, arr) ->
            runCatching { HomeRow(m.groupValues[1], json.decodeFromString<List<TitleSummary>>(arr)) }.getOrNull()
        }.filter { it.titles.isNotEmpty() }.distinctBy { it.label }

    suspend fun watchInfo(titleId: Long, fileId: Long?): WatchInfo {
        val url = if (fileId != null) "$BASE/watch/$titleId?e=$fileId" else "$BASE/watch/$titleId"
        val text = getRsc(url)
        val obj = Rsc.objectStartingWith(text, "{\"titleId\":$titleId,\"titleName\":") ?: throw IOException("Player info not found")
        return json.decodeFromString<WatchInfo>(obj)
    }

    private suspend fun getRsc(url: String): String = get(url.toHttpUrl(), rsc = true)

    private suspend fun get(url: HttpUrl, rsc: Boolean = false): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { if (rsc) header("RSC", "1") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body.string()
        }
    }

    private val ROW_REGEX = Regex(""""label":"([^"]+)","titles":(?=\[)""")
}

data class BrowseFilter(
    val kind: String = "all",
    val anime: Boolean = false,
    val language: String? = null,
    val genre: String? = null,
    val sort: String = "added",
    val order: String = "desc",
)
