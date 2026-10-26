package com.dflixtv.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Local persistence: resume positions, continue-watching list, saved subtitles and settings. */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dflix", Context.MODE_PRIVATE)
    private val json = DflixApi.json

    // region Settings

    /** OpenSubtitles language codes, e.g. "eng", "ben". */
    var subtitleLanguage: String
        get() = prefs.getString("sub_lang", "eng") ?: "eng"
        set(value) = prefs.edit { putString("sub_lang", value) }

    var openSubtitlesApiKey: String
        get() = prefs.getString("os_key", "") ?: ""
        set(value) = prefs.edit { putString("os_key", value.trim()) }

    /** Overrides the built-in OpenRouter key used for AI suggestions. */
    var openRouterApiKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit { putString("openrouter_key", value.trim()) }

    var subdlApiKey: String
        get() = prefs.getString("subdl_key", "") ?: ""
        set(value) = prefs.edit { putString("subdl_key", value.trim()) }

    /** Preferred audio: "original" (the title's original language) or a two-letter language code. */
    var audioLanguage: String
        get() = prefs.getString("audio_lang", "original") ?: "original"
        set(value) = prefs.edit { putString("audio_lang", value) }

    /** Load a subtitle in [subtitleLanguage] automatically when a video starts. */
    var autoSubtitles: Boolean
        get() = prefs.getBoolean("auto_subs", true)
        set(value) = prefs.edit { putBoolean("auto_subs", value) }

    /** Subtitle text size relative to the player default (1.0). */
    var subtitleScale: Float
        get() = prefs.getFloat("sub_scale", 1f)
        set(value) = prefs.edit { putFloat("sub_scale", value) }

    var autoPlayNext: Boolean
        get() = prefs.getBoolean("autoplay_next", true)
        set(value) = prefs.edit { putBoolean("autoplay_next", value) }

    // endregion

    // region Playback progress

    fun position(fileId: Long): Long = prefs.getLong("pos_$fileId", 0L)

    fun clearPosition(fileId: Long) = prefs.edit { remove("pos_$fileId") }

    fun savePosition(entry: ContinueEntry) {
        val list = continueWatching().filterNot { it.titleId == entry.titleId }.toMutableList()
        val finished = entry.durationMs > 0 && entry.positionMs > entry.durationMs * 0.95
        prefs.edit {
            if (finished) remove("pos_${entry.fileId}") else putLong("pos_${entry.fileId}", entry.positionMs)
            if (!finished) list.add(0, entry)
            putString("continue", json.encodeToString(list.take(30)))
        }
    }

    fun continueWatching(): List<ContinueEntry> =
        runCatching { json.decodeFromString<List<ContinueEntry>>(prefs.getString("continue", "[]")!!) }
            .getOrDefault(emptyList())

    // endregion

    // region My List

    fun myList(): List<TitleSummary> =
        runCatching { json.decodeFromString<List<TitleSummary>>(prefs.getString("my_list", "[]")!!) }
            .getOrDefault(emptyList())

    fun isInMyList(titleId: Long): Boolean = myList().any { it.id == titleId }

    /** Adds or removes [title]; returns true when it is now in the list. */
    fun toggleMyList(title: TitleSummary): Boolean {
        val list = myList()
        val present = list.any { it.id == title.id }
        val updated = if (present) list.filterNot { it.id == title.id } else listOf(title) + list
        prefs.edit { putString("my_list", json.encodeToString(updated)) }
        return !present
    }

    // endregion

    // region Recent searches

    fun recentSearches(): List<String> =
        runCatching { json.decodeFromString<List<String>>(prefs.getString("recent_searches", "[]")!!) }.getOrDefault(emptyList())

    /** Most recent first, case-insensitively de-duplicated, at most 8. */
    fun addRecentSearch(query: String) {
        val q = query.trim().takeIf { it.length >= 2 } ?: return
        val list = (listOf(q) + recentSearches().filterNot { it.equals(q, ignoreCase = true) }).take(8)
        prefs.edit { putString("recent_searches", json.encodeToString(list)) }
    }

    fun clearRecentSearches() = prefs.edit { remove("recent_searches") }

    // endregion

    // region AI suggestions

    var aiForYou: AiSuggest.Cached?
        get() = prefs.getString("ai_for_you", null)?.let { runCatching { json.decodeFromString<AiSuggest.Cached>(it) }.getOrNull() }
        set(value) = prefs.edit { if (value == null) remove("ai_for_you") else putString("ai_for_you", json.encodeToString(value)) }

    // endregion

    // region Downloaded subtitles

    fun savedSubtitles(fileId: Long): List<SavedSubtitle> =
        runCatching { json.decodeFromString<List<SavedSubtitle>>(prefs.getString("subs_$fileId", "[]")!!) }
            .getOrDefault(emptyList())

    fun addSavedSubtitle(fileId: Long, subtitle: SavedSubtitle) {
        val list = savedSubtitles(fileId).filterNot { it.path == subtitle.path } + subtitle
        prefs.edit { putString("subs_$fileId", json.encodeToString(list)) }
    }

    // endregion
}

@Serializable
data class ContinueEntry(
    val titleId: Long,
    val fileId: Long,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Serializable
data class SavedSubtitle(
    val path: String,
    val label: String,
    val language: String,
)
