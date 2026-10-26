package com.dflixtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.ContinueEntry
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.HomeRow
import com.dflixtv.app.data.MediaFile
import com.dflixtv.app.data.TitleDetails
import com.dflixtv.app.player.PlayerActivity
import com.dflixtv.app.cast.CastClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetailsViewModel(private val titleId: Long) : ViewModel() {
    var details by mutableStateOf<TitleDetails?>(null)
        private set
    var related by mutableStateOf<List<HomeRow>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { DflixApi.titlePage(titleId) }
                .onSuccess { (d, rows) -> details = d; related = rows }
                .onFailure { error = it.message }
        }
    }
}

@Composable
fun DetailsScreen(titleId: Long, open: (Long) -> Unit) {
    val vm: DetailsViewModel = viewModel(key = "details-$titleId") { DetailsViewModel(titleId) }
    val details = vm.details
    if (details == null) {
        val error = vm.error
        if (error != null) RetryMessage("Could not load title: $error", vm::load) else CenterMessage("Loading…")
        return
    }
    val context = LocalContext.current
    val store = DflixApp.store
    val isTv = LocalIsTv.current

    // Re-read resume state whenever we come back from the player.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val seasons = details.seasons
    val seasonKeys = seasons.keys.toList()
    var seasonIndex by rememberSaveable { mutableIntStateOf(0) }
    val resumeEntry = remember(resumeTick) { store.continueWatching().firstOrNull { it.titleId == titleId } }
    val playFile = resumeEntry?.let { e -> details.files.firstOrNull { it.id == e.fileId } }
        ?: seasons.values.firstOrNull()?.firstOrNull()
    var inList by remember { mutableStateOf(store.isInMyList(titleId)) }

    // Jump to the season the viewer is watching.
    LaunchedEffect(resumeEntry?.fileId) {
        val idx = seasonKeys.indexOf(playFile?.season)
        if (idx >= 0) seasonIndex = idx
    }

    val playFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusMemory = LocalFocusMemory.current
    LaunchedEffect(Unit) {
        // Returning from another title, the card we left from takes focus instead.
        if (!isTv || focusMemory.restore != null) return@LaunchedEffect
        runCatching { playFocus.requestFocus() }
        // Focusing Play scrolls it into view; keep the title and synopsis on screen instead.
        delay(60)
        listState.scrollToItem(0)
    }

    val play: (MediaFile) -> Unit = { PlayerActivity.start(context, titleId, it.id) }
    // "Play on TV" starts where this phone left off.
    val castTo: ((MediaFile) -> Unit)? = if (!isTv && CastClient.targets.isNotEmpty()) {
        { file -> CastUi.request(titleId, file.id, store.position(file.id)) }
    } else null
    val toggleList = { inList = store.toggleMyList(details.toSummary()) }
    val episodes = seasons[seasonKeys.getOrNull(seasonIndex)].orEmpty()

    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
        item(key = "header") {
            if (isTv) {
                Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.78f).dp)) {
                    Backdrop(details.backdropUrl, Modifier.fillMaxSize())
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = gutter(), bottom = 24.dp).fillMaxWidth(0.5f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DetailsInfo(details, isTv = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                            playFile?.let { file ->
                                NetflixButton(
                                    playLabel(resumeEntry, file),
                                    onClick = { play(file) },
                                    primary = true,
                                    icon = "▶",
                                    modifier = Modifier.focusRequester(playFocus),
                                )
                            }
                            if (resumeEntry != null && playFile != null) {
                                NetflixButton("Start over", onClick = {
                                    store.savePosition(resumeEntry.copy(positionMs = 0))
                                    play(playFile)
                                }, icon = "↺")
                            }
                            NetflixButton("My List", onClick = toggleList, icon = if (inList) "✓" else "+")
                        }
                        VersionChips(details, play)
                    }
                }
            } else {
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        Backdrop(details.backdropUrl ?: details.posterUrl, Modifier.fillMaxSize(), fadeLeft = false)
                    }
                    Column(
                        Modifier.padding(horizontal = gutter()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DetailsInfo(details, isTv = false)
                        playFile?.let { file ->
                            NetflixButton(playLabel(resumeEntry, file), onClick = { play(file) }, primary = true, icon = "▶", modifier = Modifier.fillMaxWidth())
                            castTo?.let { cast ->
                                NetflixButton("Play on TV", onClick = { cast(file) }, icon = "📺", modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (resumeEntry != null && playFile != null) {
                            ProgressBar(resumeEntry.progress, Modifier.fillMaxWidth())
                            NetflixButton("Start over", onClick = {
                                store.savePosition(resumeEntry.copy(positionMs = 0))
                                play(playFile)
                            }, icon = "↺", modifier = Modifier.fillMaxWidth())
                        }
                        VersionChips(details, play)
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            IconAction(if (inList) "✓" else "+", "My List", toggleList)
                        }
                    }
                }
            }
        }

        if (details.isShow && seasonKeys.isNotEmpty()) {
            item(key = "episodes-header") {
                Column(Modifier.padding(top = 12.dp)) {
                    SectionLabel("Episodes")
                    PillRow {
                        items(seasonKeys.size) { i ->
                            Pill(
                                seasonKeys[i]?.let { if (it == 0) "Specials" else "Season $it" } ?: "Episodes",
                                selected = i == seasonIndex,
                                onClick = { seasonIndex = i },
                            )
                        }
                    }
                }
            }
            if (isTv) {
                item(key = "episodes-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(bottom = 20.dp),
                    ) {
                        items(episodes, key = { it.id }) { ep ->
                            EpisodeCard(ep, progress = if (ep.id == resumeEntry?.fileId) resumeEntry.progress else null) { play(ep) }
                        }
                    }
                }
            } else {
                items(episodes, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        progress = if (ep.id == resumeEntry?.fileId) resumeEntry.progress else null,
                        onCast = castTo?.let { cast -> { cast(ep) } },
                    ) { play(ep) }
                }
            }
        }

        // Horizontal rows on phone and TV alike, like Netflix.
        vm.related.forEach { row ->
            item(key = "related-${row.label}") {
                TitleRow(row.label, row.titles, onClick = { open(it.id) })
            }
        }
    }
        // Keeps the status bar readable once content scrolls beneath it.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Background.copy(alpha = 0.85f))
        )
    }
}

@Composable
private fun DetailsInfo(details: TitleDetails, isTv: Boolean) {
    Text(
        details.title,
        fontSize = if (isTv) 44.sp else 26.sp,
        fontWeight = FontWeight.Black,
        color = Color.White,
        lineHeight = if (isTv) 48.sp else 30.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = if (isTv) 0.dp else 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        details.rating?.takeIf { it > 0 }?.let {
            Text("★ %.1f".format(it), color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        details.year?.let { Text("$it", color = Dim, fontSize = 14.sp) }
        details.certification?.takeIf { it.isNotBlank() }?.let { Badge(it) }
        if (details.isShow) {
            val count = details.seasons.size
            Text(if (count == 1) "1 Season" else "$count Seasons", color = Dim, fontSize = 14.sp)
        } else {
            details.runtime?.let { Text("${it / 60}h ${it % 60}m", color = Dim, fontSize = 14.sp) }
        }
        details.files.firstOrNull()?.quality?.let { Badge(if (it.startsWith("2160")) "4K" else "HD") }
    }
    details.tagline?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Text(
        details.overview ?: "",
        color = Color.White,
        fontSize = if (isTv) 16.sp else 14.sp,
        maxLines = if (isTv) 4 else 6,
        overflow = TextOverflow.Ellipsis,
    )
    GenreLine(details.genreList, Modifier.padding(top = 2.dp))
}

private fun playLabel(resume: ContinueEntry?, file: MediaFile): String {
    val episode = if (file.season != null && file.episode != null) " S${file.season}:E${file.episode}" else ""
    return (if (resume != null) "Resume" else "Play") + episode
}

/** Movies sometimes come in several versions (e.g. 1080p and 2160p). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionChips(details: TitleDetails, play: (MediaFile) -> Unit) {
    if (details.isShow || details.files.size < 2) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        details.files.forEach { file ->
            Pill(
                listOfNotNull(file.quality, file.codec, file.size?.let(::formatSize)).joinToString(" · "),
                selected = false,
                onClick = { play(file) },
            )
        }
    }
}

/** Icon-over-label action, like Netflix's "My List" button on phones. */
@Composable
private fun IconAction(icon: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .focusRing()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(icon, color = Color.White, fontSize = 24.sp)
        Text(label, color = Dim, fontSize = 12.sp)
    }
}

/** Phone episode row: thumbnail with progress, title, and synopsis underneath. */
@Composable
private fun EpisodeRow(file: MediaFile, progress: Float?, onCast: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .focusRing()
            .clickable(onClick = onClick)
            .padding(horizontal = gutter(), vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(CardColor)) {
                AsyncImage(
                    model = file.stillUrl,
                    contentDescription = file.episodeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "▶",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x88000000))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                progress?.let { ProgressBar(it, Modifier.fillMaxWidth().align(Alignment.BottomStart)) }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    listOfNotNull(file.episode?.let { "$it." }, file.episodeTitle).joinToString(" "),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(file.quality, file.size?.let(::formatSize)).joinToString(" · "),
                    color = Dim,
                    fontSize = 12.sp,
                )
            }
            onCast?.let {
                Text(
                    "📺",
                    fontSize = 20.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = it).padding(10.dp),
                )
            }
        }
        file.episodeOverview?.let {
            Text(it, color = Dim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** TV episode tile. */
@Composable
private fun EpisodeCard(file: MediaFile, progress: Float?, onClick: () -> Unit) {
    Column(Modifier.width(300.dp)) {
        FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = file.stillUrl,
                contentDescription = file.episodeTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            progress?.let { ProgressBar(it, Modifier.fillMaxWidth().align(Alignment.BottomStart)) }
        }
        Text(
            listOfNotNull(file.episode?.let { "$it." }, file.episodeTitle).joinToString(" "),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        file.episodeOverview?.let {
            Text(it, color = Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "%.0f MB".format(bytes / (1L shl 20).toDouble())
