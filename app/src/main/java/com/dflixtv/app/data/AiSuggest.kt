package com.dflixtv.app.data

import com.dflixtv.app.BuildConfig
import com.dflixtv.app.DflixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Title suggestions from a free OpenRouter model. The model only names titles; each one is looked
 * up in the DFLIX library and kept only if it is actually there, so every suggestion is playable.
 */
object AiSuggest {
    /** Free models, tried in order; free endpoints are often rate-limited, so OpenRouter falls through. */
    private val MODELS = listOf(
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "google/gemma-4-31b-it:free",
        "dots-studio/dots-3-note-preview:free",
    )
    private const val REFRESH_MS = 24 * 60 * 60 * 1000L

    private val apiKey: String get() = DflixApp.store.openRouterApiKey.ifBlank { BuildConfig.OPENROUTER_KEY }

    val isAvailable: Boolean get() = apiKey.isNotBlank()

    // Models can take a minute to answer.
    private val http by lazy { DflixApp.http.newBuilder().readTimeout(150, TimeUnit.SECONDS).build() }

    @Serializable
    data class Cached(val basis: String, val at: Long, val titles: List<TitleSummary>)

    /** Last "Top Picks" result, shown immediately while a fresh one may be on its way. */
    fun cachedForYou(): List<TitleSummary> = DflixApp.store.aiForYou?.titles.orEmpty()

    /**
     * Picks based on what the viewer watched and saved. Returns null when there is no history yet or
     * the cached picks are still current for the same history.
     */
    suspend fun refreshForYou(): List<TitleSummary>? {
        if (!isAvailable) return null
        val store = DflixApp.store
        val watched = store.continueWatching().map { it.title }.filter { it.isNotBlank() }.distinct().take(15)
        val saved = store.myList().map { listOfNotNull(it.title, it.year?.let { y -> "($y)" }).joinToString(" ") }.take(15)
        if (watched.isEmpty() && saved.isEmpty()) return null
        val basis = (watched + saved).joinToString("|")
        val cached = store.aiForYou
        if (cached != null && cached.basis == basis && System.currentTimeMillis() - cached.at < REFRESH_MS) return null

        val prompt = buildString {
            if (watched.isNotEmpty()) append("Recently watched: ${watched.joinToString("; ")}.\n")
            if (saved.isNotEmpty()) append("Saved to watch later: ${saved.joinToString("; ")}.\n")
            append("Suggest 30 other movies or TV series this viewer would enjoy, matching their genres, languages and tone. ")
            append("Mix well-known and lesser-known picks. Don't repeat any title listed above.")
        }
        val known = store.continueWatching().map { it.titleId }.toSet() + store.myList().map { it.id }
        val titles = resolve(ask(prompt), exclude = known)
        if (titles.isNotEmpty()) store.aiForYou = Cached(basis, System.currentTimeMillis(), titles)
        return titles
    }

    /** Free-text request from Search, e.g. "space movies like Interstellar". */
    suspend fun search(request: String): List<TitleSummary> =
        resolve(ask("Suggest 30 movies or TV series that match this request: \"$request\"."), exclude = emptySet())

    private suspend fun ask(prompt: String): List<String> {
        val body = buildJsonObject {
            putJsonArray("models") { MODELS.forEach { add(it) } }
            // Reasoning makes free models several times slower without better picks.
            putJsonObject("reasoning") { put("enabled", false) }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "You recommend movies and TV series. Reply with one title per line as: Title (Year). No numbering, no notes, no other text.")
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
        val text = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "DFLIX TV")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) throw IOException("AI service: HTTP ${response.code}")
                DflixApi.json.parseToJsonElement(payload).jsonObject
            }
        }
        val content = (text["choices"]?.jsonArray?.firstOrNull() as? JsonObject)
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw IOException(text["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "AI service gave no answer")
        return content.lines()
    }

    /** Finds each suggested "Title (Year)" in the library; unmatched suggestions are dropped. */
    private suspend fun resolve(lines: List<String>, exclude: Set<Long>): List<TitleSummary> = coroutineScope {
        val picks = lines.mapNotNull { line ->
            LINE.find(line.trim())?.let { m -> m.groupValues[1].trim() to m.groupValues[2].toIntOrNull() }
                ?.takeIf { it.first.length > 1 }
        }.distinctBy { normalize(it.first) }.take(30)
        val limit = Semaphore(6)
        picks.map { (title, year) ->
            async {
                limit.withPermit {
                    val results = runCatching { DflixApi.search(title) }.getOrDefault(emptyList())
                    val sameName = results.filter { normalize(it.title) == normalize(title) }
                    sameName.firstOrNull { year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1 }
                }
            }
        }.awaitAll().filterNotNull().filter { it.id !in exclude }.distinctBy { it.id }
    }

    private fun normalize(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

    // "1. Title (2014)", "- Title (2014)", "Title (2014) – note"; the year is optional.
    // A note is only cut off after a spaced dash, so "Spider-Man: No Way Home" stays whole.
    private val LINE = Regex("""^(?:\d+[.)]\s*|[-*•]\s+)?\**(.+?)\**\s*(?:\((\d{4})[^)]*\))?(?:\s+[-–—]\s+.*)?$""")
}
