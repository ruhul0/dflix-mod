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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    var myList by mutableStateOf<List<TitleSummary>>(emptyList())
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

    fun refreshLocal() {
        continueWatching = DflixApp.store.continueWatching()
        myList = DflixApp.store.myList()
    }
}

@Composable
fun HomeScreen(open: (Long) -> Unit, topInset: Dp, listState: LazyListState) {
    val vm: HomeViewModel = viewModel()
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshLocal() }

    if (vm.rows.isEmpty()) {
        val error = vm.error
        if (error != null) RetryMessage("Could not reach dflix.live: $error", vm::load) else CenterMessage("Loading…")
        return
    }

    val trending = vm.rows.firstOrNull { it.label.startsWith("Trending") }
    val others = vm.rows.filter { it !== trending }

    // The billboard is the first list item, so it scrolls away under the header like Netflix.
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
        vm.featured?.let { featured ->
            item(key = "hero") {
                if (isTv) TvBillboard(featured, topInset, onInfo = { open(featured.id) }, onListChanged = vm::refreshLocal)
                else PhoneBillboard(featured, topInset, onInfo = { open(featured.id) }, onListChanged = vm::refreshLocal)
            }
        }
        if (vm.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                ContinueRow(vm.continueWatching) { PlayerActivity.start(context, it.titleId, it.fileId) }
            }
        }
        if (vm.myList.isNotEmpty()) {
            item(key = "mylist") { TitleRow("My List", vm.myList, onClick = { open(it.id) }) }
        }
        trending?.let { row ->
            item(key = "top10") {
                Top10Row("Top 10 Today", row.titles, onClick = { open(it.id) }, onFocused = { if (isTv) vm.featured = it })
            }
        }
        items(others, key = { it.label }) { row ->
            // On TV the billboard follows the focused card; "Recently Added" uses wide tiles for variety.
            TitleRow(
                row.label,
                row.titles,
                onClick = { open(it.id) },
                onFocused = { if (isTv) vm.featured = it },
                landscape = isTv && row.label.startsWith("Recently"),
            )
        }
    }
}

@Composable
private fun TvBillboard(title: TitleSummary, topInset: Dp, onInfo: () -> Unit, onListChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val height = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    var inList by remember(title.id) { mutableStateOf(DflixApp.store.isInMyList(title.id)) }
    Box(Modifier.fillMaxWidth().height(height)) {
        Backdrop(title.backdropUrl, Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = gutter(), bottom = 28.dp, top = topInset)
                .fillMaxWidth(0.45f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title.title,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 52.sp,
            )
            MetaLine(title)
            Text(
                title.cleanOverview ?: "",
                fontSize = 16.sp,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                NetflixButton(
                    "Play",
                    onClick = { scope.launch { playTitle(context, title.id) } },
                    primary = true,
                    icon = "▶",
                    memoryKey = "hero/play",
                )
                NetflixButton("More info", onClick = onInfo, icon = "ⓘ", memoryKey = "hero/info")
                NetflixButton(
                    "My List",
                    onClick = { inList = DflixApp.store.toggleMyList(title); onListChanged() },
                    icon = if (inList) "✓" else "+",
                    memoryKey = "hero/list",
                )
            }
        }
    }
}

@Composable
private fun PhoneBillboard(title: TitleSummary, topInset: Dp, onInfo: () -> Unit, onListChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inList by remember(title.id) { mutableStateOf(DflixApp.store.isInMyList(title.id)) }
    Box(Modifier.fillMaxWidth()) {
        // Blurred-feel backdrop behind a centered poster card, as in Netflix's phone app.
        Backdrop(title.backdropUrl ?: title.posterUrl, Modifier.matchParentSize(), fadeLeft = false)
        Column(
            Modifier.fillMaxWidth().padding(top = topInset + 8.dp, start = 28.dp, end = 28.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardColor)
            ) {
                AsyncImage(
                    model = title.posterUrl,
                    contentDescription = title.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xEE000000))))
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GenreLine(title.genreList)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetflixButton(
                            "Play",
                            onClick = { scope.launch { playTitle(context, title.id) } },
                            primary = true,
                            icon = "▶",
                            modifier = Modifier.weight(1f),
                        )
                        NetflixButton(
                            "My List",
                            onClick = { inList = DflixApp.store.toggleMyList(title); onListChanged() },
                            icon = if (inList) "✓" else "+",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Text(
                "ⓘ  More info",
                color = Dim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .focusRing()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onInfo)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
fun MetaLine(title: TitleSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        title.rating?.takeIf { it > 0 }?.let {
            Text("★ %.1f".format(it), color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        title.year?.let { Text("$it", color = Color.White, fontSize = 15.sp) }
        if (title.isShow) Badge("Series")
        title.quality?.let { Badge(if (it.startsWith("2160")) "4K" else "HD") }
        GenreLine(title.genreList)
    }
}

@Composable
private fun ContinueRow(entries: List<ContinueEntry>, onClick: (ContinueEntry) -> Unit) {
    val isTv = LocalIsTv.current
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionLabel("Continue Watching")
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp),
        ) {
            items(entries, key = { it.titleId }) { entry ->
                Column(Modifier.width(if (isTv) 280.dp else 180.dp)) {
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
                        Text(
                            "▶",
                            color = Color.White,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0x99000000))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                        ProgressBar(entry.progress, Modifier.fillMaxWidth().align(Alignment.BottomStart))
                    }
                    Text(
                        listOfNotNull(entry.title, entry.subtitle?.substringBefore(" · ")).joinToString(" · "),
                        fontSize = 13.sp,
                        color = Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
