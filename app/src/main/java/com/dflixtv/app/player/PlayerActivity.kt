package com.dflixtv.app.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dflixtv.app.DflixApp
import com.dflixtv.app.R
import com.dflixtv.app.data.ContinueEntry
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.MediaFile
import com.dflixtv.app.data.SavedSubtitle
import com.dflixtv.app.data.TitleDetails
import com.dflixtv.app.data.WatchInfo
import com.dflixtv.app.subs.Language
import com.dflixtv.app.subs.SubtitleFormats
import com.dflixtv.app.subs.SubtitleQuery
import com.dflixtv.app.subs.SubtitleRepository
import com.dflixtv.app.subs.SubtitleResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var nextButton: Button
    private lateinit var player: ExoPlayer

    private val store get() = DflixApp.store
    private lateinit var subtitles: SubtitleRepository

    private var titleId = 0L
    private var fileId = 0L
    private var details: TitleDetails? = null
    private var watchInfo: WatchInfo? = null
    private var activeLocalSubtitle: SavedSubtitle? = null
    private var pendingSubtitleId: String? = null
    private var subtitleOffsetMs = 0L
    private var progressJob: Job? = null
    private var dialog: Dialog? = null

    private val currentFile: MediaFile? get() = details?.files?.firstOrNull { it.id == fileId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        subtitles = SubtitleRepository(this, store)

        titleId = intent.getLongExtra(EXTRA_TITLE_ID, 0)
        fileId = intent.getLongExtra(EXTRA_FILE_ID, 0)

        playerView = findViewById(R.id.player_view)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.title)
        nextButton = findViewById(R.id.btn_next)
        findViewById<Button>(R.id.btn_subtitles).setOnClickListener { showSubtitleMenu() }
        findViewById<Button>(R.id.btn_sub_delay).setOnClickListener { showSyncDialog() }
        nextButton.setOnClickListener { playNext() }

        player = buildPlayer()
        playerView.player = player
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = visibility
        })
        playerView.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerView.isControllerFullyVisible) playerView.hideController() else finish()
            }
        })

        load(fileId, resume = true)
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(this)
            // Prefer hardware/passthrough audio, fall back to FFmpeg for AC3/E-AC3/DTS/TrueHD.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val http = OkHttpDataSource.Factory(DflixApp.http)
        val dataSource = DefaultDataSource.Factory(this, http)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                playWhenReady = true
                addListener(playerListener)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                saveProgress()
                if (store.autoPlayNext && nextFileId() != null) playNext() else finish()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val wanted = pendingSubtitleId ?: return
            val group = tracks.groups.firstOrNull { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).id?.contains(wanted) == true }
            } ?: return
            pendingSubtitleId = null
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        }

        override fun onPlayerError(error: PlaybackException) {
            toast("Playback error: ${error.errorCodeName}")
        }
    }

    /** Loads title/player info for [newFileId] and starts playback. */
    private fun load(newFileId: Long, resume: Boolean) {
        saveProgress()
        fileId = newFileId
        watchInfo = null
        activeLocalSubtitle = store.savedSubtitles(fileId).lastOrNull()
        subtitleOffsetMs = 0
        pendingSubtitleId = activeLocalSubtitle?.let { subtitleId(it) }
        updateHeader()

        // Start the stream right away; subtitles and metadata arrive shortly after.
        val start = if (resume) store.position(fileId) else 0L
        player.setMediaItem(buildMediaItem(), start)
        player.prepare()

        lifecycleScope.launch {
            if (details == null) details = runCatching { DflixApi.titlePage(titleId).first }.getOrNull()
            watchInfo = runCatching { DflixApi.watchInfo(titleId, fileId) }.getOrNull()
            updateHeader()
            if (watchInfo?.subtitles?.isNotEmpty() == true) reloadKeepingPosition()
        }
        startProgressLoop()
    }

    private fun buildMediaItem(): MediaItem {
        val configs = buildList {
            watchInfo?.subtitles?.forEach { sub ->
                add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(DflixApi.absolute(sub.src)))
                        .setId("dflix-${sub.id}")
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLabel(sub.label ?: "DFLIX")
                        .setLanguage(sub.lang?.takeUnless { it == "und" })
                        .build()
                )
            }
            store.savedSubtitles(fileId).filter { File(it.path).exists() }.forEach { sub ->
                add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(sub.path)))
                        .setId(subtitleId(sub))
                        .setMimeType(SubtitleFormats.mimeType(sub.path))
                        .setLabel(sub.label)
                        .setLanguage(sub.language)
                        .build()
                )
            }
        }
        return MediaItem.Builder()
            .setUri(DflixApi.streamUrl(fileId))
            .setMediaId(fileId.toString())
            .setSubtitleConfigurations(configs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(headerText()).build())
            .build()
    }

    private fun reloadKeepingPosition() {
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(buildMediaItem(), position)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private fun subtitleId(sub: SavedSubtitle) = "local-" + File(sub.path).nameWithoutExtension

    private fun headerText(): String {
        val file = currentFile
        val name = details?.title ?: watchInfo?.titleName ?: ""
        return listOfNotNull(name, file?.episodeCode, file?.episodeTitle).joinToString(" · ")
    }

    private fun updateHeader() {
        titleView.text = headerText()
        nextButton.visibility = if (nextFileId() != null) View.VISIBLE else View.GONE
    }

    private fun nextFileId(): Long? {
        watchInfo?.nextEpisode?.fileId?.let { return it }
        val files = details?.takeIf { it.isShow }?.seasons?.values?.flatten() ?: return null
        val index = files.indexOfFirst { it.id == fileId }
        return files.getOrNull(index + 1)?.takeIf { index >= 0 }?.id
    }

    private fun playNext() {
        val next = nextFileId() ?: return
        load(next, resume = true)
    }

    // region Progress

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) saveProgress()
            }
        }
    }

    private fun saveProgress() {
        if (!::player.isInitialized || fileId == 0L) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val position = player.currentPosition
        if (position < 15_000) return
        val file = currentFile
        store.savePosition(
            ContinueEntry(
                titleId = titleId,
                fileId = fileId,
                title = details?.title ?: watchInfo?.titleName ?: "",
                subtitle = listOfNotNull(file?.episodeCode, file?.episodeTitle).joinToString(" · ").ifEmpty { null },
                imageUrl = file?.stillUrl ?: details?.backdropUrl ?: watchInfo?.poster?.let(DflixApi::absolute),
                positionMs = position,
                durationMs = duration,
            )
        )
    }

    // endregion

    // region Subtitles

    private fun subtitleQuery(language: Language): SubtitleQuery {
        val file = currentFile
        return SubtitleQuery(
            title = details?.title ?: watchInfo?.titleName ?: "",
            year = details?.year,
            imdbId = file?.imdbId ?: details?.imdbId,
            tmdbId = details?.tmdbId,
            season = file?.season,
            episode = file?.episode,
            language = language,
            fileName = file?.fileName,
            fileSize = file?.size,
            streamUrl = DflixApi.streamUrl(fileId),
        )
    }

    private fun showSubtitleMenu() {
        val language = Language.byCode3(store.subtitleLanguage)
        val saved = store.savedSubtitles(fileId).filter { File(it.path).exists() }
        val options = buildList {
            add("Search online (${language.name})" to { searchSubtitles(language) })
            add("Search in another language…" to { pickLanguageThenSearch() })
            saved.reversed().forEach { sub -> add("Use saved: ${sub.label}" to { selectLocal(sub) }) }
            add("Turn subtitles off" to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        showList("Subtitles", options.map { it.first }) { options[it].second() }
    }

    private fun pickLanguageThenSearch() {
        showList("Subtitle language", Language.ALL.map { it.name }) { index ->
            val language = Language.ALL[index]
            store.subtitleLanguage = language.code3
            searchSubtitles(language)
        }
    }

    private fun searchSubtitles(language: Language) {
        val query = subtitleQuery(language)
        val progress = showProgress("Searching subtitles…")
        lifecycleScope.launch {
            val outcome = runCatching { subtitles.search(query) }
            progress.dismiss()
            val result = outcome.getOrElse { toast("Search failed: ${it.message}"); return@launch }
            if (result.results.isEmpty()) {
                toast(if (result.errors.isEmpty()) "No subtitles found" else result.errors.joinToString("\n"))
                return@launch
            }
            val items = result.results.take(60)
            showList("${items.size} subtitles · ${language.name}", items.map { "${it.displayName}\n${it.detail}" }) {
                downloadSubtitle(items[it], query)
            }
        }
    }

    private fun downloadSubtitle(result: SubtitleResult, query: SubtitleQuery) {
        val progress = showProgress("Downloading subtitle…")
        lifecycleScope.launch {
            val saved = runCatching { subtitles.download(result, query, fileId) }
            progress.dismiss()
            saved.onSuccess { selectLocal(it); toast("Subtitle loaded") }
                .onFailure { toast("Download failed: ${it.message}") }
        }
    }

    private fun selectLocal(sub: SavedSubtitle) {
        activeLocalSubtitle = sub
        subtitleOffsetMs = 0
        pendingSubtitleId = subtitleId(sub)
        reloadKeepingPosition()
    }

    private fun showSyncDialog() {
        val sub = activeLocalSubtitle
        if (sub == null) {
            toast("Sync works on downloaded subtitles. Use \"Find subtitles\" first.")
            return
        }
        val steps = listOf(-2000L, -1000L, -500L, -250L, 250L, 500L, 1000L, 2000L)
        val labels = steps.map { (if (it > 0) "+" else "") + "%.2fs".format(it / 1000.0) + if (it < 0) "  (show earlier)" else "  (show later)" }
        showList("Subtitle sync · current offset %+.2fs".format(subtitleOffsetMs / 1000.0), labels) { index ->
            lifecycleScope.launch {
                subtitles.shift(sub, steps[index])
                subtitleOffsetMs += steps[index]
                pendingSubtitleId = subtitleId(sub)
                reloadKeepingPosition()
                showSyncDialog()
            }
        }
    }

    // endregion

    // region UI helpers

    private fun showList(title: String, items: List<String>, onPick: (Int) -> Unit) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showProgress(message: String): Dialog =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(message)
            .setCancelable(true)
            .show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // endregion

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    if (event.action == KeyEvent.ACTION_UP) playNext()
                    return true
                }
                return playerView.dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (player.isPlaying) player.pause() else player.play()
                    playerView.showController()
                }
                return true
            }
        }
        if (!playerView.isControllerFullyVisible) {
            // With controls hidden, left/right seek directly; any other key reveals the controls.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack() else player.seekForward()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                playerView.requestFocus()
                playerView.showController()
            }
            return true
        }
        // Keep the controls (and our top bar) visible while the user is navigating them.
        playerView.showController()
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        player.release()
    }

    companion object {
        private const val EXTRA_TITLE_ID = "title_id"
        private const val EXTRA_FILE_ID = "file_id"

        fun start(context: Context, titleId: Long, fileId: Long) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_TITLE_ID, titleId)
                    .putExtra(EXTRA_FILE_ID, fileId)
            )
        }
    }
}
