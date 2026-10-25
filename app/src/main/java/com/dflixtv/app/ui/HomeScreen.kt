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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import coil3.compose.AsyncImage
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.ContinueEntry
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.HomeRow
import com.dflixtv.app.data.TitleSummary
import com.dflixtv.app.player.PlayerActivity
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    var rows by mutableStateOf<List<HomeRow>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var continueWatching by mutableStateOf<List<ContinueEntry>>(emptyList())
        private set
    var featured by mutableStateOf<TitleSummary?>(null)

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { DflixApi.homeRows() }
                .onSuccess { result ->
                    rows = result
                    if (featured == null) {
                        featured = (result.firstOrNull { it.label.startsWith("Trending") } ?: result.firstOrNull())
                            ?.titles?.firstOrNull { it.backdropPath != null }
                    }
                }
                .onFailure { error = it.message ?: "Could not load" }
        }
    }

    fun refreshContinue() {
        continueWatching = DflixApp.store.continueWatching()
    }
}

@Composable
fun HomeScreen(open: (Long) -> Unit) {
    val vm: HomeViewModel = viewModel()
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshContinue() }

    if (vm.rows.isEmpty()) {
        CenterMessage(vm.error?.let { "Could not reach dflix.live: $it" } ?: "Loading…")
        return
    }

    // The hero is the first list item, so it scrolls away with the rows.
    LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 48.dp)) {
        vm.featured?.let { featured ->
            item(key = "hero") { Hero(featured, onOpen = { open(featured.id) }) }
        }
        if (vm.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                ContinueRow(vm.continueWatching) { PlayerActivity.start(context, it.titleId, it.fileId) }
            }
        }
        items(vm.rows, key = { it.label }) { row ->
            // On TV the hero follows the focused card, like other TV apps.
            TitleRow(row.label, row.titles, onClick = { open(it.id) }, onFocused = { if (isTv) vm.featured = it })
        }
    }
}

@Composable
private fun Hero(title: TitleSummary, onOpen: () -> Unit) {
    val isTv = LocalIsTv.current
    Box(Modifier.fillMaxWidth().height(if (isTv) 300.dp else 260.dp)) {
        Backdrop(title.backdropUrl, Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = gutter(), vertical = 16.dp)
                .fillMaxWidth(if (isTv) 0.55f else 1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    title.year?.toString(),
                    title.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                    title.genreList.take(3).joinToString(", ").ifEmpty { null },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                title.cleanOverview ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (isTv) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.85f),
            )
            // On phones the hero is tappable through this button; on TV focus drives the hero instead.
            if (!isTv) {
                Row {
                    Button(
                        onClick = onOpen,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Text("Details") }
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(entries: List<ContinueEntry>, onClick: (ContinueEntry) -> Unit) {
    val isTv = LocalIsTv.current
    Column(Modifier.padding(bottom = 20.dp)) {
        SectionLabel("Continue Watching")
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(entries, key = { it.titleId }) { entry ->
                Column(Modifier.width(if (isTv) 260.dp else 200.dp)) {
                    FocusCard(
                        onClick = { onClick(entry) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    ) {
                        AsyncImage(
                            model = entry.imageUrl,
                            contentDescription = entry.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ProgressBar(entry.progress, Modifier.fillMaxWidth().align(Alignment.BottomStart))
                    }
                    Text(entry.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                    entry.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}
