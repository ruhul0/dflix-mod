package com.dflixtv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Text
import com.dflixtv.app.data.BrowseFilter
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.TitleSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BrowseViewModel(initial: BrowseFilter) : ViewModel() {
    var filter by mutableStateOf(initial)
        private set
    val items = mutableStateListOf<TitleSummary>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var page = 0
    private var hasMore = true
    private var job: Job? = null

    init {
        loadMore()
    }

    fun applyFilter(newFilter: BrowseFilter) {
        if (newFilter == filter) return
        filter = newFilter
        job?.cancel()
        items.clear()
        page = 0
        hasMore = true
        loading = false
        loadMore()
    }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        job = viewModelScope.launch {
            runCatching { DflixApi.browse(filter, page + 1) }
                .onSuccess { response ->
                    val known = items.mapTo(HashSet()) { it.id }
                    items += response.items.filter { it.id !in known }
                    page = response.page
                    hasMore = response.hasMore
                    error = null
                }
                .onFailure { error = it.message }
            loading = false
        }
    }
}

private val SORTS = listOf("added" to "Recently added", "rating" to "Top rated", "year" to "Newest", "title" to "A–Z")
private val LANGUAGES = listOf(null to "All", "en" to "English", "bn" to "Bengali", "hi" to "Hindi", "ko" to "Korean",
    "ja" to "Japanese", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "zh" to "Chinese", "tr" to "Turkish", "es" to "Spanish")

@Composable
fun BrowseScreen(key: String, initial: BrowseFilter, open: (Long) -> Unit) {
    val vm: BrowseViewModel = viewModel(key = key) { BrowseViewModel(initial) }
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SORTS) { (value, label) ->
                FilterChip(
                    selected = vm.filter.sort == value,
                    onClick = { vm.applyFilter(vm.filter.copy(sort = value, order = if (value == "title") "asc" else "desc")) },
                ) { Text(label) }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LANGUAGES) { (value, label) ->
                FilterChip(selected = vm.filter.language == value, onClick = { vm.applyFilter(vm.filter.copy(language = value)) }) {
                    Text(label)
                }
            }
        }
        if (vm.items.isEmpty()) {
            CenterMessage(if (vm.loading) "Loading…" else vm.error ?: "Nothing here")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(vm.items, key = { _, t -> t.id }) { index, title ->
                if (index >= vm.items.size - 12) LaunchedEffect(vm.items.size) { vm.loadMore() }
                PosterCard(title, onClick = { open(title.id) }, width = 150.dp)
            }
        }
    }
}
