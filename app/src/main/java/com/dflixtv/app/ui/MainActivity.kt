package com.dflixtv.app.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dflixtv.app.data.BrowseFilter
import com.dflixtv.app.update.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val tv = isTv()
        setContent {
            CompositionLocalProvider(LocalIsTv provides tv) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Brand,
                        background = Background,
                        surface = Background,
                        secondaryContainer = Brand,
                    )
                ) {
                    Surface(Modifier.fillMaxSize(), color = Background) {
                        App()
                    }
                }
            }
        }
    }

    // Keyboard support: Esc works like the remote's Back button. Arrows and Enter already map to D-pad.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

sealed interface Screen {
    data object Main : Screen
    data class Details(val titleId: Long) : Screen
}

private enum class Tab(val label: String) { Home("Home"), Movies("Movies"), Shows("TV Shows"), Anime("Anime"), Search("Search"), Settings("Settings") }

@Composable
private fun App() {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Main) }
    val holder = rememberSaveableStateHolder()
    val open: (Long) -> Unit = { backStack.add(Screen.Details(it)) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { Updater.checkOnLaunch(context) }

    BackHandler(enabled = backStack.size > 1) { backStack.removeAt(backStack.lastIndex) }

    val current = backStack.last()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        holder.SaveableStateProvider(current.toString() + backStack.size) {
            when (current) {
                Screen.Main -> MainScreen(open)
                is Screen.Details -> DetailsScreen(current.titleId, open)
            }
        }
    }
}

@Composable
private fun MainScreen(open: (Long) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries
    val firstTab = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    LaunchedEffect(Unit) { if (isTv) runCatching { firstTab.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DFLIX",
                style = MaterialTheme.typography.titleLarge,
                color = Brand,
                modifier = Modifier.padding(start = gutter()),
            )
            PillRow {
                itemsIndexed(tabs) { index, tab ->
                    Pill(
                        tab.label,
                        selected = index == selected,
                        onClick = { selected = index },
                        modifier = if (index == 0) Modifier.focusRequester(firstTab) else Modifier,
                    )
                }
            }
        }
        when (tabs[selected]) {
            Tab.Home -> HomeScreen(open)
            Tab.Movies -> BrowseScreen("movies", BrowseFilter(kind = "movie"), open)
            Tab.Shows -> BrowseScreen("shows", BrowseFilter(kind = "tv"), open)
            Tab.Anime -> BrowseScreen("anime", BrowseFilter(kind = "all", anime = true), open)
            Tab.Search -> SearchScreen(open)
            Tab.Settings -> SettingsScreen()
        }
    }
}
