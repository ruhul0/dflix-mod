package com.dflixtv.app.player

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.dflixtv.app.ui.isTv
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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.dflixtv.app.DflixApp
import com.dflixtv.app.R
import com.dflixtv.app.data.ContinueEntry
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.MediaFile
import com.dflixtv.app.data.NewEpisodes
import com.dflixtv.app.data.SavedSubtitle
import com.dflixtv.app.data.TitleDetails
import com.dflixtv.app.data.WatchInfo
import com.dflixtv.app.subs.Language
import com.dflixtv.app.subs.SubtitleFormats
import com.dflixtv.app.subs.SubtitleQuery
import com.dflixtv.app.subs.SubtitleRepository
import com.dflixtv.app.subs.SubtitleResult
import com.dflixtv.app.tv.WatchNext
import com.dflixtv.app.cast.CastStatus
import com.dflixtv.app.cast.NowPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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
    private lateinit var loading: View
    private lateinit var pipButton: Button
    private var wasInPip = false
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
    private var networkRetries = 0
    private var startOverrideMs: Long? = null
    private val sleepTimer = SleepTimer()
    private lateinit var nextCard: NextEpisodeCard
    private var nextCardDismissed = false

    private val currentFile: MediaFile? get() = details?.files?.firstOrNull { it.id == fileId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        subtitles = SubtitleRepository(this, store)

        titleId = intent.getLongExtra(EXTRA_TITLE_ID, 0)
        fileId = intent.getLongExtra(EXTRA_FILE_ID, 0)
        startOverrideMs = intent.getLongExtra(EXTRA_START_MS, -1).takeIf { it >= 0 }

        playerView = findViewById(R.id.player_view)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.title)
        nextButton = findViewById(R.id.btn_next)
        loading = findViewById(R.id.loading)
        findViewById<Button>(R.id.btn_subtitles).setOnClickListener { showAudioSubtitlePanel() }
        nextButton.setOnClickListener { playNext() }
        nextCard = NextEpisodeCard(findViewById(R.id.next_card), findViewById(R.id.next_title), findViewById(R.id.btn_next_now))
        findViewById<Button>(R.id.btn_next_now).setOnClickListener { hideNextCard(); playNext() }
        findViewById<Button>(R.id.btn_next_cancel).setOnClickListener { nextCardDismissed = true; hideNextCard() }
        pipButton = findViewById(R.id.btn_pip)
        pipButton.visibility = if (pipSupported) View.VISIBLE else View.GONE
        pipButton.setOnClickListener { enterPip() }
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)

        player = buildPlayer()
        playerView.player = player
        // Audio, subtitles and speed all live in our own Audio & Subtitles panel.
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        applySubtitleScale()
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = visibility
            // Lift subtitles above the seek bar while the controls are showing.
            playerView.subtitleView?.setBottomPaddingFraction(
                if (visibility == View.VISIBLE) 0.2f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
            )
        })
        playerView.requestFocus()
        if (!isTv()) PhoneGestures(this, playerView, player, findViewById(R.id.gesture_hint)).attach()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    nextCard.isShowing -> { nextCardDismissed = true; hideNextCard() }
                    playerView.isControllerFullyVisible -> playerView.hideController()
                    else -> finish()
                }
            }
        })

        load(fileId, resume = true)
    }

    /**
     * Picks the best audio track: the preferred (or original) language first; within it ExoPlayer's
     * selector already favours the most channels and highest bitrate the device can play.
     * Commentary and descriptive-audio tracks are avoided by preferring main-role tracks.
     */
    private fun applyAudioPreference() {
        val preferred = store.audioLanguage
        val languages = buildList {
            if (preferred == "original") details?.originalLanguage?.let(::add) else add(preferred)
            add("en")
        }.distinct()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*languages.toTypedArray())
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .build()
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(this)
            // Prefer hardware/passthrough audio, fall back to FFmpeg for AC3/E-AC3/DTS/TrueHD.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val http = OkHttpDataSource.Factory(DflixApp.http)
        val dataSource = DefaultDataSource.Factory(this, http)
        // The buffer lives on the Java heap, which is small on cheap TV sticks: use a quarter of it.
        val heapMb = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .setTargetBufferBytes((heapMb / 4).coerceIn(32, 128) * 1024 * 1024)
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
                if (store.autoPlayNext && nextFileId() != null && !sleepTimer.afterEpisode) playNext() else finish()
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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) networkRetries = 0
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

        override fun onPlayerError(error: PlaybackException) {
            // Wi-Fi hiccups are common on TVs: retry network errors from the same position a few times.
            val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED until PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            if (network && networkRetries < MAX_NETWORK_RETRIES) {
                networkRetries++
                loading.visibility = View.VISIBLE
                lifecycleScope.launch {
                    delay(3_000L * networkRetries)
                    player.prepare()
                    loading.visibility = View.GONE
                }
                return
            }
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
        nextCardDismissed = false
        hideNextCard()
        updateHeader()

        // Prepare paused: the video buffers and exposes its embedded tracks while we gather
        // subtitles, so playback starts once, with everything attached, instead of reloading.
        // A phone's "Play on TV" passes where the viewer was on the phone.
        val start = startOverrideMs?.also { startOverrideMs = null } ?: if (resume) store.position(fileId) else 0L
        val loadingFileId = fileId
        player.playWhenReady = false
        player.setMediaItem(buildMediaItem(), start)
        player.prepare()
        setStarting(true)

        lifecycleScope.launch {
            val detailsJob = async { details ?: runCatching { DflixApi.titlePage(titleId).first }.getOrNull() }
            val watchJob = async { runCatching { DflixApi.watchInfo(titleId, loadingFileId) }.getOrNull() }
            details = detailsJob.await()
            watchInfo = watchJob.await()
            details?.takeIf { it.isShow }?.let { NewEpisodes.markSeen(titleId, it.files.size) }
            if (loadingFileId != fileId) return@launch
            applyAudioPreference()
            updateHeader()

            var rebuild = watchInfo?.subtitles?.isNotEmpty() == true
            if (store.autoSubtitles && activeLocalSubtitle == null) {
                val search = async { findAutoSubtitle(loadingFileId) }
                val found = withTimeoutOrNull(AUTO_SUBTITLE_WAIT_MS) { search.await() }
                if (found != null) {
                    activeLocalSubtitle = found
                    pendingSubtitleId = subtitleId(found)
                    rebuild = true
                } else if (search.isActive) {
                    // Slow network: start now and attach the subtitle when it arrives.
                    launch { search.await()?.let { if (loadingFileId == fileId) selectLocal(it) } }
                }
            }
            if (loadingFileId != fileId) return@launch
            if (rebuild) {
                player.setMediaItem(buildMediaItem(), start)
                player.prepare()
            }
            player.playWhenReady = true
            setStarting(false)
        }
        startProgressLoop()
    }

    /** While starting, show a spinner instead of the paused player's controls. */
    private fun setStarting(starting: Boolean) {
        loading.visibility = if (starting) View.VISIBLE else View.GONE
        playerView.controllerAutoShow = !starting
        if (starting) playerView.hideController()
    }

    private fun buildMediaItem(): MediaItem {
        val configs = buildList {
            watchInfo?.subtitles?.filter { TrackLabels.isEnglishOrUnknown(it.lang) }?.forEach { sub ->
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
            var tick = 0
            while (isActive) {
                delay(1_000)
                checkSleepTimer()
                updateNextCard()
                publishNowPlaying()
                if (++tick % 10 == 0 && player.isPlaying) saveProgress()
            }
        }
    }

    /** Drives the "Next episode" card; when its countdown runs out the next episode starts. */
    private fun updateNextCard() {
        val next = nextFileId()
        val duration = player.duration
        val eligible = store.autoPlayNext && next != null && duration != C.TIME_UNSET &&
            !nextCardDismissed && !sleepTimer.afterEpisode && !isInPictureInPictureMode
        val remaining = if (eligible) duration - player.currentPosition else null
        val due = nextCard.tick(remaining, player.isPlaying) {
            val file = details?.files?.firstOrNull { it.id == next }
            listOfNotNull(file?.episodeCode, file?.episodeTitle).joinToString(" · ").ifEmpty { "Up next" }
        }
        if (due) {
            playerView.requestFocus()
            saveProgress()
            playNext()
        }
    }

    private fun checkSleepTimer() {
        if (!sleepTimer.consumeIfDue()) return
        saveProgress()
        player.pause()
        toast("Sleep timer: stopped")
        // Leaving the player lets the TV's screen saver and sleep take over.
        finish()
    }

    private fun hideNextCard() {
        if (nextCard.hide()) playerView.requestFocus()
    }

    /** Keeps the phone remote's view of the TV current. */
    private fun publishNowPlaying() {
        val file = currentFile
        NowPlaying.status = CastStatus(
            active = true,
            titleId = titleId,
            fileId = fileId,
            title = details?.title ?: watchInfo?.titleName ?: "",
            subtitle = listOfNotNull(file?.episodeCode, file?.episodeTitle).joinToString(" · ").ifEmpty { null },
            playing = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
    }

    private fun saveProgress() {
        if (!::player.isInitialized || fileId == 0L) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val position = player.currentPosition
        if (position < 15_000) return
        val file = currentFile
        val next = nextFileId()
        val isShow = details?.isShow == true || next != null
        if (position > duration * 0.95 && next != null) {
            // Finished an episode: Continue Watching offers the next one, like Netflix.
            val nextFile = details?.files?.firstOrNull { it.id == next }
            val upNext = ContinueEntry(
                titleId = titleId,
                fileId = next,
                title = details?.title ?: watchInfo?.titleName ?: "",
                subtitle = listOfNotNull(nextFile?.episodeCode, nextFile?.episodeTitle).joinToString(" · ").ifEmpty { null },
                imageUrl = nextFile?.stillUrl ?: details?.backdropUrl ?: watchInfo?.poster?.let(DflixApi::absolute),
                positionMs = 0,
                durationMs = 0,
            )
            store.savePosition(upNext)
            store.clearPosition(fileId)
            WatchNext.publish(this, upNext, isShow = true, upNext = true)
            return
        }
        val entry = ContinueEntry(
            titleId = titleId,
            fileId = fileId,
            title = details?.title ?: watchInfo?.titleName ?: "",
            subtitle = listOfNotNull(file?.episodeCode, file?.episodeTitle).joinToString(" · ").ifEmpty { null },
            imageUrl = file?.stillUrl ?: details?.backdropUrl ?: watchInfo?.poster?.let(DflixApi::absolute),
            positionMs = position,
            durationMs = duration,
        )
        store.savePosition(entry)
        if (position > duration * 0.95) WatchNext.remove(this, titleId)
        else WatchNext.publish(this, entry, isShow, upNext = false)
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
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 },
            streamUrl = DflixApi.streamUrl(fileId),
        )
    }

    /** Netflix-style panel: audio tracks, subtitle tracks, playback speed and subtitle size. */
    private fun showAudioSubtitlePanel() {
        dialog?.dismiss()
        val tracks = player.currentTracks.groups
        val original = details?.originalLanguage

        val audioTracks = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        val audio = audioTracks.mapIndexed { position, (group, index) ->
            val format = group.getTrackFormat(index)
            val fallback = if (audioTracks.size == 1) "Original" else "Audio ${position + 1}"
            PanelOption(TrackLabels.audioLabel(format, original, fallback), group.isTrackSelected(index)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
                // Remember the choice for the next video, as Netflix does.
                format.language?.substringBefore('-')?.takeUnless { it == "und" }?.let { lang ->
                    store.audioLanguage = if (lang == original) "original" else lang
                }
            }
        }

        val textGroups = tracks.filter { it.type == C.TRACK_TYPE_TEXT }
        val englishTrack = { group: Tracks.Group, index: Int -> TrackLabels.isEnglishOrUnknown(group.getTrackFormat(index).language) }
        val subtitlesOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
            textGroups.none { it.isSelected }
        val subtitleOptions = buildList {
            add(PanelOption("Off", subtitlesOff) {
                activeLocalSubtitle = null
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
            textGroups.forEach { group ->
                (0 until group.length).filter { group.isTrackSupported(it) && englishTrack(group, it) }.forEach { index ->
                    val format = group.getTrackFormat(index)
                    add(PanelOption(TrackLabels.subtitleLabel(format), !subtitlesOff && group.isTrackSelected(index)) {
                        selectSubtitleTrack(group, index)
                    })
                }
            }
            add(PanelOption("Find more online…") { searchSubtitles(ENGLISH) })
            if (activeLocalSubtitle != null && !subtitlesOff) add(PanelOption("Adjust timing…") { showSyncDialog() })
        }

        val speed = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { value ->
            val label = if (value == 1f) "1x" else "${value.toString().removeSuffix(".0")}x"
            PanelOption(label, player.playbackParameters.speed == value) { player.setPlaybackSpeed(value) }
        }

        val minutesLeft = sleepTimer.minutesLeft
        val sleep = buildList {
            add(PanelOption("Off", sleepTimer.isOff) { toast(sleepTimer.set(null, false)) })
            if (nextFileId() != null || details?.isShow == true) {
                add(PanelOption("This episode", sleepTimer.afterEpisode) { toast(sleepTimer.set(null, true)) })
            }
            listOf(15, 30, 60, 90).forEach { m ->
                val active = sleepTimer.isRunning(m)
                add(PanelOption(if (active) "$m min ($minutesLeft left)" else "$m min", active) { toast(sleepTimer.set(m, false)) })
            }
        }

        val sizes = listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.3f, "Extra large" to 1.6f).map { (label, scale) ->
            PanelOption(label, store.subtitleScale == scale) {
                store.subtitleScale = scale
                applySubtitleScale()
            }
        }

        dialog = showOptionsPanel(
            this,
            listOf(
                PanelColumn("Audio", audio, weight = 1.15f),
                PanelColumn("Subtitles", subtitleOptions, weight = 1.45f),
                PanelColumn("Speed", speed, weight = 0.6f),
                PanelColumn("Sleep timer", sleep, weight = 0.95f),
                PanelColumn("Text size", sizes, weight = 0.9f),
            ),
            focusColumn = 1,
        )
    }

    private fun selectSubtitleTrack(group: Tracks.Group, index: Int) {
        val format = group.getTrackFormat(index)
        // Downloaded subtitles can be re-timed; embedded and DFLIX ones can't.
        activeLocalSubtitle = store.savedSubtitles(fileId).firstOrNull { format.id?.contains(subtitleId(it)) == true }
        subtitleOffsetMs = 0
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun applySubtitleScale() {
        playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * store.subtitleScale)
    }


    /**
     * Picks a subtitle in the preferred language without user action. An embedded track is selected
     * in place and null returned; otherwise the best online match is downloaded and returned so the
     * caller can attach it before playback starts.
     */
    private suspend fun findAutoSubtitle(forFileId: Long): SavedSubtitle? {
        val language = ENGLISH
        val tag = language.code2.substringBefore('-')

        // The paused player exposes the container's tracks (embedded subtitles show up here).
        val deadline = System.currentTimeMillis() + 5_000
        while (player.currentTracks.isEmpty && System.currentTimeMillis() < deadline) delay(200)
        if (forFileId != fileId) return null

        val existing = player.currentTracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_TEXT &&
                (0 until group.length).any { group.getTrackFormat(it).language?.startsWith(tag) == true }
        }
        if (existing != null) {
            val index = (0 until existing.length).first { existing.getTrackFormat(it).language?.startsWith(tag) == true }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(existing.mediaTrackGroup, index))
                .build()
            return null
        }

        val query = subtitleQuery(language)
        // Results arrive ranked by fit (matching IDs, length, release name, hash, popularity).
        val best = runCatching { subtitles.search(query).results }.getOrNull()?.firstOrNull() ?: return null
        if (forFileId != fileId) return null
        return runCatching { subtitles.download(best, query, forFileId) }.getOrNull()
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
            toast("Timing can be adjusted on downloaded subtitles. Use \"Find more online\" first.")
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
        // While the "Next episode" card is up, arrows and OK move between its buttons.
        if (nextCard.isShowing && event.keyCode in CARD_KEYS) return super.dispatchKeyEvent(event)
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
        // Stopping right after PiP means the user closed the floating window.
        if (wasInPip) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        NowPlaying.status = CastStatus()
        dialog?.dismiss()
        unregisterReceiver(pipReceiver)
        player.release()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A title picked while the player sits in PiP replaces what's playing.
        setIntent(intent)
        val newTitle = intent.getLongExtra(EXTRA_TITLE_ID, titleId)
        if (newTitle != titleId) details = null
        titleId = newTitle
        startOverrideMs = intent.getLongExtra(EXTRA_START_MS, -1).takeIf { it >= 0 }
        load(intent.getLongExtra(EXTRA_FILE_ID, fileId), resume = true)
    }

    // region Picture-in-picture

    private val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_PIP_ACTION, 0)) {
                PIP_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                PIP_BACK -> player.seekBack()
                PIP_FORWARD -> player.seekForward()
                CONTROL_STOP -> finish()
            }
            publishNowPlaying()
        }
    }

    private fun enterPip() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        playerView.hideController()
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** Leaving the app with Home while a video plays shrinks it into PiP (older Android). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipSupported && player.isPlaying && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    private fun updatePipParams() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            // Android only accepts ratios between 1:2.39 and 2.39:1.
            val r = size.width * size.pixelWidthHeightRatio / size.height
            Rational((r.coerceIn(0.42f, 2.39f) * 1000).toInt(), 1000)
        } else Rational(16, 9)
        fun action(id: Int, icon: Int, title: String) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(
                this, id,
                Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val playing = player.isPlaying
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(
                listOf(
                    action(PIP_BACK, androidx.media3.ui.R.drawable.exo_icon_rewind, "Back 10 seconds"),
                    action(
                        PIP_PLAY_PAUSE,
                        if (playing) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play,
                        if (playing) "Pause" else "Play",
                    ),
                    action(PIP_FORWARD, androidx.media3.ui.R.drawable.exo_icon_fastforward, "Forward 10 seconds"),
                )
            )
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true) }
            .build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) wasInPip = true
        else if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) wasInPip = false
        // The tiny window shows just the video; controls return when it's expanded again.
        playerView.useController = !isInPictureInPictureMode
        topBar.visibility = View.GONE
        dialog?.takeIf { isInPictureInPictureMode }?.dismiss()
    }

    // endregion

    companion object {
        private const val EXTRA_TITLE_ID = "title_id"
        private const val AUTO_SUBTITLE_WAIT_MS = 8_000L
        private const val MAX_NETWORK_RETRIES = 3
        private val ENGLISH = Language.byCode3("eng")
        private val CARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        private const val ACTION_PIP_CONTROL = "com.dflixtv.app.PIP_CONTROL"
        private const val EXTRA_PIP_ACTION = "pip_action"
        private const val PIP_PLAY_PAUSE = 1
        private const val PIP_BACK = 2
        private const val PIP_FORWARD = 3
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_START_MS = "start_ms"

        // Remote-control actions, shared with the PiP buttons.
        const val CONTROL_TOGGLE = PIP_PLAY_PAUSE
        const val CONTROL_BACK = PIP_BACK
        const val CONTROL_FORWARD = PIP_FORWARD
        const val CONTROL_STOP = 4

        fun controlIntent(context: Context, action: Int): Intent =
            Intent(ACTION_PIP_CONTROL).setPackage(context.packageName).putExtra(EXTRA_PIP_ACTION, action)

        /** [startMs] overrides the saved resume position (used by "Play on TV"). */
        fun start(context: Context, titleId: Long, fileId: Long, startMs: Long? = null) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_TITLE_ID, titleId)
                    .putExtra(EXTRA_FILE_ID, fileId)
                    .apply { startMs?.let { putExtra(EXTRA_START_MS, it) } }
                    .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
