package com.dflixtv.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * What the phone and TV apps share over the local network: Continue Watching and My List, with
 * the time of every addition and removal so the newest change wins on each title.
 */
@Serializable
data class SyncData(
    val continueWatching: List<ContinueEntry> = emptyList(),
    /** titleId → when it left Continue Watching (finished). */
    val continueRemoved: Map<Long, Long> = emptyMap(),
    val myList: List<TitleSummary> = emptyList(),
    /** titleId → when it was added to My List. */
    val myListAdded: Map<Long, Long> = emptyMap(),
    /** titleId → when it was removed from My List. */
    val myListRemoved: Map<Long, Long> = emptyMap(),
)

object SyncMerge {
    private const val TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000

    fun merge(a: SyncData, b: SyncData, now: Long = System.currentTimeMillis()): SyncData {
        val continueRemoved = latest(a.continueRemoved, b.continueRemoved, now)
        val entries = (a.continueWatching + b.continueWatching)
            .groupBy { it.titleId }
            .mapNotNull { (id, candidates) ->
                candidates.maxBy { it.updatedAt }.takeIf { it.updatedAt > (continueRemoved[id] ?: Long.MIN_VALUE) }
            }
            .sortedByDescending { it.updatedAt }
            .take(30)

        val added = latest(a.myListAdded, b.myListAdded, now = null)
        val listRemoved = latest(a.myListRemoved, b.myListRemoved, now)
        val titles = (a.myList + b.myList).distinctBy { it.id }
            .filter { (added[it.id] ?: 0L) > (listRemoved[it.id] ?: Long.MIN_VALUE) }
            .sortedByDescending { added[it.id] ?: 0L }

        return SyncData(
            continueWatching = entries,
            // A removal older than the entry it lost to is no longer needed.
            continueRemoved = continueRemoved.filterKeys { id -> entries.none { it.titleId == id } },
            myList = titles,
            myListAdded = added.filterKeys { id -> titles.any { it.id == id } },
            myListRemoved = listRemoved.filterKeys { id -> titles.none { it.id == id } },
        )
    }

    /** Per key, the later time; removals older than [now] minus the TTL are dropped. */
    private fun latest(a: Map<Long, Long>, b: Map<Long, Long>, now: Long?): Map<Long, Long> =
        (a.keys + b.keys).associateWith { maxOf(a[it] ?: Long.MIN_VALUE, b[it] ?: Long.MIN_VALUE) }
            .filterValues { now == null || it > now - TOMBSTONE_TTL_MS }
}

/** Bumped when synced data arrives, so screens re-read Continue Watching and My List. */
object SyncState {
    var version by mutableIntStateOf(0)
        private set

    fun changed() {
        version++
    }
}
