package com.dflixtv.app.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.dflixtv.app.data.ContinueEntry
import com.dflixtv.app.ui.MainActivity
import com.dflixtv.app.ui.isTv
import java.util.concurrent.Executors

/**
 * Mirrors Continue Watching into the Google TV / Android TV home screen's "Continue watching" row,
 * so a show can be resumed straight from the launcher. One row per title, keyed by its id.
 */
object WatchNext {
    private const val TAG = "WatchNext"
    private val io = Executors.newSingleThreadExecutor()

    /** [upNext] marks an episode that hasn't been started yet (the one after a finished episode). */
    fun publish(context: Context, entry: ContinueEntry, isShow: Boolean, upNext: Boolean) {
        if (!supported(context)) return
        val app = context.applicationContext
        io.execute {
            runCatching {
                val existing = find(app, entry.titleId)
                // The viewer removed it from the launcher row; don't push it back.
                if (existing != null && !existing.second) return@runCatching
                val program = WatchNextProgram.Builder()
                    .setWatchNextType(
                        if (upNext) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                        else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    )
                    .setType(
                        if (isShow) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE
                        else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE
                    )
                    .setTitle(entry.title)
                    .apply { entry.subtitle?.let { setEpisodeTitle(it) } }
                    .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                    .apply { entry.imageUrl?.let { setPosterArtUri(Uri.parse(it)) } }
                    .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                    .setLastPlaybackPositionMillis(entry.positionMs.toInt())
                    .setDurationMillis(entry.durationMs.toInt())
                    .setInternalProviderId(entry.titleId.toString())
                    .setIntentUri(Uri.parse(MainActivity.playIntent(app, entry.titleId, entry.fileId).toUri(Intent.URI_INTENT_SCHEME)))
                    .build()
                val values = program.toContentValues()
                if (existing == null) {
                    app.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                } else {
                    app.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing.first), values, null, null)
                }
            }.onFailure { Log.w(TAG, "publish failed", it) }
        }
    }

    /** Removes the title once it has been watched to the end. */
    fun remove(context: Context, titleId: Long) {
        if (!supported(context)) return
        val app = context.applicationContext
        io.execute {
            runCatching {
                find(app, titleId)?.let { (id, _) ->
                    app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
                }
            }.onFailure { Log.w(TAG, "remove failed", it) }
        }
    }

    private fun supported(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.isTv()

    /** Row id and whether it is still shown, for our row of [titleId]. */
    @SuppressLint("RestrictedApi")
    private fun find(context: Context, titleId: Long): Pair<Long, Boolean>? {
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(
                TvContractCompat.WatchNextPrograms._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == titleId.toString()) return cursor.getLong(0) to (cursor.getInt(2) != 0)
            }
        }
        return null
    }
}
