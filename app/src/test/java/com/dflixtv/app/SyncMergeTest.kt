package com.dflixtv.app

import com.dflixtv.app.data.ContinueEntry
import com.dflixtv.app.data.SyncData
import com.dflixtv.app.data.SyncMerge
import com.dflixtv.app.data.TitleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone ↔ TV sync: the newest change wins per title, and removals don't come back. */
class SyncMergeTest {
    private val now = 1_000_000_000_000L

    private fun entry(titleId: Long, fileId: Long, at: Long, pos: Long = 60_000) =
        ContinueEntry(titleId = titleId, fileId = fileId, title = "T$titleId", positionMs = pos, durationMs = 3_600_000, updatedAt = at)

    private fun title(id: Long) = TitleSummary(id = id, title = "T$id")

    @Test
    fun newerProgressWinsAndBothSidesKeepTheirOtherTitles() {
        val phone = SyncData(continueWatching = listOf(entry(1, 11, at = now - 10, pos = 900_000), entry(2, 21, at = now - 50)))
        val tv = SyncData(continueWatching = listOf(entry(1, 12, at = now - 100), entry(3, 31, at = now - 20)))
        val merged = SyncMerge.merge(phone, tv, now)
        assertEquals(listOf(1L, 3L, 2L), merged.continueWatching.map { it.titleId })
        // Title 1: the phone's more recent progress wins.
        assertEquals(11L, merged.continueWatching.first().fileId)
        assertEquals(900_000L, merged.continueWatching.first().positionMs)
    }

    @Test
    fun finishingOnOneDeviceRemovesItOnTheOther() {
        val phone = SyncData(continueRemoved = mapOf(1L to now - 5))
        val tv = SyncData(continueWatching = listOf(entry(1, 11, at = now - 60)))
        val merged = SyncMerge.merge(phone, tv, now)
        assertTrue(merged.continueWatching.isEmpty())
        assertEquals(mapOf(1L to now - 5), merged.continueRemoved)
    }

    @Test
    fun watchingAgainAfterFinishingBringsItBack() {
        val phone = SyncData(continueRemoved = mapOf(1L to now - 500))
        val tv = SyncData(continueWatching = listOf(entry(1, 11, at = now - 5)))
        val merged = SyncMerge.merge(phone, tv, now)
        assertEquals(listOf(1L), merged.continueWatching.map { it.titleId })
        assertTrue(merged.continueRemoved.isEmpty())
    }

    @Test
    fun myListAddsAndRemovalsResolveByTime() {
        val phone = SyncData(
            myList = listOf(title(1), title(2)),
            myListAdded = mapOf(1L to now - 100, 2L to now - 100),
        )
        val tv = SyncData(
            myList = listOf(title(3)),
            myListAdded = mapOf(3L to now - 10),
            // Title 2 was removed on the TV after the phone added it.
            myListRemoved = mapOf(2L to now - 50),
        )
        val merged = SyncMerge.merge(phone, tv, now)
        assertEquals(listOf(3L, 1L), merged.myList.map { it.id })
        assertEquals(mapOf(2L to now - 50), merged.myListRemoved)
    }

    @Test
    fun itemsSavedBeforeSyncExistedAreKeptUnlessRemoved() {
        // Older app versions stored My List without add times.
        val phone = SyncData(myList = listOf(title(1), title(2)))
        val tv = SyncData(myListRemoved = mapOf(2L to now - 5))
        assertEquals(listOf(1L), SyncMerge.merge(phone, tv, now).myList.map { it.id })
    }

    @Test
    fun oldRemovalsExpire() {
        val ancient = now - 200L * 24 * 60 * 60 * 1000
        val merged = SyncMerge.merge(SyncData(continueRemoved = mapOf(1L to ancient)), SyncData(), now)
        assertTrue(merged.continueRemoved.isEmpty())
    }
}
