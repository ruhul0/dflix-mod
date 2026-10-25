package com.dflixtv.app.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.dflixtv.app.data.BrowseFilter
import com.dflixtv.app.update.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Brand, background = Color(0xFF0B0B0F))) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    colors = SurfaceDefaults.colors(containerColor = Color(0xFF0B0B0F)),
                ) {
                    App()
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
    holder.SaveableStateProvider(current.toString() + backStack.size) {
        when (current) {
            Screen.Main -> MainScreen(open)
            is Screen.Details -> DetailsScreen(current.titleId, open)
        }
    }
}

@Composable
private fun MainScreen(open: (Long) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = Tab.entries
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 48.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DFLIX", style = MaterialTheme.typography.headlineSmall, color = Brand, modifier = Modifier.padding(end = 32.dp))
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, tab ->
                    Tab(selected = index == selected, onFocus = { selected = index }) {
                        Text(tab.label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
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
}
