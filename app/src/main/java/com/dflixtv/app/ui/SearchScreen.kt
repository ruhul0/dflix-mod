package com.dflixtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
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
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.AiSuggest
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
    /** True while the results are AI picks for the query rather than title matches. */
    var aiMode by mutableStateOf(false)
        private set
    var aiError by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    var recent by mutableStateOf(DflixApp.store.recentSearches())
        private set

    /** Remembers the query once it led somewhere: a result was opened or the AI was asked. */
    fun rememberQuery() {
        DflixApp.store.addRecentSearch(query)
        recent = DflixApp.store.recentSearches()
    }

    fun clearRecent() {
        DflixApp.store.clearRecentSearches()
        recent = emptyList()
    }

    fun askAi() {
        val text = query.trim().takeIf { it.isNotEmpty() } ?: return
        rememberQuery()
        job?.cancel()
        aiMode = true
        aiError = null
        results = emptyList()
        job = viewModelScope.launch {
            searching = true
            runCatching { AiSuggest.search(text) }
                .onSuccess { results = it }
                .onFailure { aiError = it.message }
            searching = false
        }
    }

    fun onQuery(text: String) {
        query = text
        aiMode = false
        aiError = null
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

/** Set when the remote's Search key is pressed; the Search screen consumes it by starting voice input. */
object VoiceSearchRequests {
    var pending by mutableStateOf(false)
}

private val speechIntent: Intent
    get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title, or what you feel like watching")

@Composable
fun SearchScreen(open: (Long) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current
    val context = LocalContext.current
    val voiceAvailable = remember { speechIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::onQuery)
    }
    // The remote's Search key lands here; each press is consumed once.
    LaunchedEffect(VoiceSearchRequests.pending) {
        if (VoiceSearchRequests.pending) {
            VoiceSearchRequests.pending = false
            if (voiceAvailable) runCatching { voice.launch(speechIntent) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = gutter(), vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchTextField(
                value = vm.query,
                onValueChange = vm::onQuery,
                placeholder = if (AiSuggest.isAvailable) "Search, or describe what you feel like watching" else "Search movies, shows, anime…",
                // Keyboard "done" goes to Ask AI when it's there, otherwise down to the results.
                onDone = {
                    val aiButton = AiSuggest.isAvailable && vm.query.isNotBlank()
                    focusManager.moveFocus(if (aiButton) FocusDirection.Right else FocusDirection.Down)
                },
                modifier = if (isTv) Modifier.fillMaxWidth(0.6f) else Modifier.weight(1f),
            )
            if (voiceAvailable) {
                NetflixButton("Voice", onClick = { runCatching { voice.launch(speechIntent) } }, icon = "🎤")
            }
            if (AiSuggest.isAvailable && vm.query.isNotBlank()) {
                NetflixButton("Ask AI", onClick = vm::askAi, icon = "✨")
            }
        }
        if (vm.aiMode && vm.results.isNotEmpty()) SectionLabel("AI picks for \"${vm.query.trim()}\"")
        when {
            vm.results.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (LocalIsTv.current) 150.dp else 105.dp),
                contentPadding = PaddingValues(start = gutter(), end = gutter(), top = 12.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(vm.results, key = { it.id }) { t ->
                    PosterCard(t, onClick = { vm.rememberQuery(); open(t.id) }, width = if (LocalIsTv.current) 150.dp else 105.dp)
                }
            }
            vm.searching -> CenterMessage(if (vm.aiMode) "Asking AI… this can take up to a minute" else "Searching…")
            vm.aiError != null -> RetryMessage("AI suggestions failed: ${vm.aiError}", vm::askAi)
            vm.aiMode -> CenterMessage("None of the AI's picks are on DFLIX. Try describing it differently.")
            vm.query.isNotBlank() -> CenterMessage(
                if (AiSuggest.isAvailable) "No results for \"${vm.query}\". Try ✨ Ask AI." else "No results for \"${vm.query}\""
            )
            vm.recent.isNotEmpty() -> Column(Modifier.padding(top = 8.dp)) {
                SectionLabel("Recent searches")
                PillRow {
                    items(vm.recent) { q -> Pill(q, selected = false, onClick = { vm.onQuery(q) }) }
                    item { Pill("Clear", selected = false, onClick = vm::clearRecent) }
                }
            }
            else -> CenterMessage("Type to search the DFLIX library")
        }
    }
}
