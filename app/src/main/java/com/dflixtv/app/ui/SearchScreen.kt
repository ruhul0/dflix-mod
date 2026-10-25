package com.dflixtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.TitleSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<TitleSummary>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun onQuery(text: String) {
        query = text
        job?.cancel()
        if (text.isBlank()) {
            results = emptyList()
            return
        }
        job = viewModelScope.launch {
            delay(350)
            searching = true
            results = runCatching { DflixApi.search(text.trim()) }.getOrDefault(emptyList())
            searching = false
        }
    }
}

@Composable
fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
        cursorBrush = SolidColor(Brand),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(Color(0xFF1C1C22), RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Brand else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp)
                inner()
            }
        },
    )
}

@Composable
fun SearchScreen(open: (Long) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        SearchTextField(
            value = vm.query,
            onValueChange = vm::onQuery,
            placeholder = "Search movies, shows, anime…",
            onDone = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
            modifier = Modifier.padding(horizontal = gutter(), vertical = 12.dp).fillMaxWidth(if (LocalIsTv.current) 0.6f else 1f),
        )
        when {
            vm.results.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (LocalIsTv.current) 150.dp else 105.dp),
                contentPadding = PaddingValues(start = gutter(), end = gutter(), top = 12.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(vm.results, key = { it.id }) { t -> PosterCard(t, onClick = { open(t.id) }, width = if (LocalIsTv.current) 150.dp else 105.dp) }
            }
            vm.searching -> CenterMessage("Searching…")
            vm.query.isNotBlank() -> CenterMessage("No results for \"${vm.query}\"")
            else -> CenterMessage("Type to search the DFLIX library")
        }
    }
}
