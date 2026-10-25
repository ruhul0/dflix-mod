package com.dflixtv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    var focused by mutableStateOf<TitleSummary?>(null)

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { DflixApi.homeRows() }
                .onSuccess { rows = it; if (focused == null) focused = it.firstOrNull()?.titles?.firstOrNull() }
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshContinue() }

    if (vm.rows.isEmpty()) {
        CenterMessage(vm.error?.let { "Could not reach dflix.live: $it" } ?: "Loading…")
        return
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(vm.focused?.backdropUrl, Modifier.fillMaxWidth().height(340.dp))
        Column(Modifier.fillMaxSize()) {
            FocusedHeader(vm.focused)
            LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 48.dp)) {
                if (vm.continueWatching.isNotEmpty()) {
                    item(key = "continue") {
                        ContinueRow(vm.continueWatching) { PlayerActivity.start(context, it.titleId, it.fileId) }
                    }
                }
                items(vm.rows, key = { it.label }) { row ->
                    TitleRow(row.label, row.titles, onClick = { open(it.id) }, onFocused = { vm.focused = it })
                }
            }
        }
    }
}

@Composable
private fun FocusedHeader(title: TitleSummary?) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .padding(start = 48.dp, end = 48.dp, top = 8.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        if (title == null) return@Column
        Text(title.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                title.year?.toString(),
                title.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                title.runtime?.let { "${it}m" },
                title.genreList.take(3).joinToString(", ").ifEmpty { null },
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            title.cleanOverview ?: "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.55f),
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ContinueRow(entries: List<ContinueEntry>, onClick: (ContinueEntry) -> Unit) {
    Column(Modifier.padding(bottom = 20.dp)) {
        SectionLabel("Continue Watching")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(entries, key = { it.titleId }) { entry ->
                Column(Modifier.width(260.dp)) {
                    Card(
                        onClick = { onClick(entry) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        shape = CardDefaults.shape(MaterialTheme.shapes.small),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = entry.imageUrl,
                                contentDescription = entry.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            ProgressBar(entry.progress, Modifier.fillMaxWidth().align(Alignment.BottomStart))
                        }
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
