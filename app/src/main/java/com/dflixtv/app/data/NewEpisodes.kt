package com.dflixtv.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.dflixtv.app.DflixApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString

/**
 * Tracks episode counts of the shows the viewer follows (Continue Watching and My List) and flags
 * those that gained episodes since the viewer last opened them. [ids] is Compose state, so posters
 * can show a "New episodes" tag wherever the show appears.
 */
object NewEpisodes {
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private val prefs by lazy { DflixApp.instance.getSharedPreferences("new_episodes", Context.MODE_PRIVATE) }
    private val json get() = DflixApi.json

    var ids by mutableStateOf(load<Set<Long>>("flagged") ?: emptySet())
        private set

    private fun counts(): Map<Long, Int> = load("counts") ?: emptyMap()

    /** The viewer has seen [episodeCount] episodes' worth of this show: clears its flag. */
    fun markSeen(titleId: Long, episodeCount: Int) {
        save("counts", counts() + (titleId to episodeCount))
        if (titleId in ids) {
            ids = ids - titleId
            save("flagged", ids)
        }
    }

    /** At most once a day: fetch each followed show and flag those with more episodes than before. */
    suspend fun checkIfDue() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked_at", 0) < CHECK_INTERVAL_MS) return
        prefs.edit { putLong("checked_at", now) }

        val store = DflixApp.store
        val followed = (store.continueWatching().map { it.titleId } + store.myList().filter { it.isShow }.map { it.id })
            .distinct().take(25)
        val limit = Semaphore(3)
        val latest = coroutineScope {
            followed.map { id ->
                async {
                    limit.withPermit {
                        runCatching { DflixApi.titlePage(id).first }.getOrNull()
                            ?.takeIf { it.isShow }?.let { id to it.files.size }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val known = counts()
        val grown = latest.filter { (id, count) -> known[id]?.let { count > it } == true }.map { it.first }
        // First sighting of a show sets its baseline without flagging it.
        save("counts", known + latest.filter { it.first !in known })
        if (grown.isNotEmpty()) {
            ids = ids + grown
            save("flagged", ids)
        }
    }

    private inline fun <reified T> load(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> save(key: String, value: T) = prefs.edit { putString(key, json.encodeToString(value)) }
}
