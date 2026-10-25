package com.dflixtv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.HomeRow
import com.dflixtv.app.data.MediaFile
import com.dflixtv.app.data.TitleDetails
import com.dflixtv.app.player.PlayerActivity
import kotlinx.coroutines.launch

class DetailsViewModel(private val titleId: Long) : ViewModel() {
    var details by mutableStateOf<TitleDetails?>(null)
        private set
    var related by mutableStateOf<List<HomeRow>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
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
        CenterMessage(vm.error?.let { "Could not load title: $it" } ?: "Loading…")
        return
    }
    val context = LocalContext.current
    val store = DflixApp.store
    // Re-read resume state whenever we come back from the player.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val seasons = details.seasons
    val seasonKeys = seasons.keys.toList()
    var seasonIndex by rememberSaveable { mutableIntStateOf(0) }
    val resumeEntry = remember(resumeTick) { store.continueWatching().firstOrNull { it.titleId == titleId } }
    val playFile = resumeEntry?.let { e -> details.files.firstOrNull { it.id == e.fileId } }
        ?: seasons.values.firstOrNull()?.firstOrNull()

    // Jump to the season the user is watching.
    LaunchedEffect(resumeEntry?.fileId) {
        val season = playFile?.season
        val idx = seasonKeys.indexOf(season)
        if (idx >= 0) seasonIndex = idx
    }

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(details.backdropUrl, Modifier.fillMaxWidth().height(420.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 24.dp)) {
                    AsyncImage(
                        model = details.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(180.dp).aspectRatio(2f / 3f),
                    )
                    Column(Modifier.padding(start = 32.dp).fillMaxWidth(0.7f)) {
                        Text(details.title, style = MaterialTheme.typography.displaySmall, maxLines = 2)
                        Text(
                            listOfNotNull(
                                details.year?.toString(),
                                details.certification,
                                details.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                                details.runtime?.let { "${it / 60}h ${it % 60}m" }?.takeIf { !details.isShow },
                                if (details.isShow) "${seasons.size} seasons · ${details.files.size} episodes" else null,
                                details.genreList.joinToString(", ").ifEmpty { null },
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        details.tagline?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
                        }
                        Text(
                            details.overview ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (playFile != null) {
                                val label = buildString {
                                    append(if (resumeEntry != null) "Resume" else "Play")
                                    playFile.episodeCode?.let { append(" $it") }
                                }
                                Button(onClick = { PlayerActivity.start(context, titleId, playFile.id) }, Modifier.focusRequester(playFocus)) {
                                    Text(label)
                                }
                                if (resumeEntry != null) {
                                    OutlinedButton(onClick = {
                                        store.savePosition(resumeEntry.copy(positionMs = 0))
                                        PlayerActivity.start(context, titleId, playFile.id)
                                    }) { Text("Start over") }
                                }
                            } else {
                                Text("No playable files", color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        // Movies can come in several versions (e.g. 1080p and 2160p).
                        if (!details.isShow && details.files.size > 1) {
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                details.files.forEach { file ->
                                    OutlinedButton(onClick = { PlayerActivity.start(context, titleId, file.id) }) {
                                        Text(listOfNotNull(file.quality, file.codec, file.size?.let(::formatSize)).joinToString(" · "))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (details.isShow && seasonKeys.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        items(seasonKeys.size) { i ->
                            FilterChip(selected = i == seasonIndex, onClick = { seasonIndex = i }) {
                                Text(seasonKeys[i]?.let { if (it == 0) "Specials" else "Season $it" } ?: "Episodes")
                            }
                        }
                    }
                }
                item {
                    val episodes = seasons[seasonKeys.getOrNull(seasonIndex)].orEmpty()
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 28.dp),
                    ) {
                        items(episodes, key = { it.id }) { ep ->
                            EpisodeCard(ep, progress = if (ep.id == resumeEntry?.fileId) resumeEntry.progress else null) {
                                PlayerActivity.start(context, titleId, ep.id)
                            }
                        }
                    }
                }
            }

            items(vm.related, key = { it.label }) { row ->
                TitleRow(row.label, row.titles, onClick = { open(it.id) })
            }
        }
    }
}

@Composable
private fun EpisodeCard(file: MediaFile, progress: Float?, onClick: () -> Unit) {
    Column(Modifier.width(280.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(MaterialTheme.shapes.small),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = file.stillUrl,
                    contentDescription = file.episodeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                file.quality?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                progress?.let { ProgressBar(it, Modifier.fillMaxWidth().align(Alignment.BottomStart)) }
            }
        }
        Text(
            listOfNotNull(file.episode?.let { "E$it" }, file.episodeTitle).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        file.episodeOverview?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "%.0f MB".format(bytes / (1L shl 20).toDouble())
