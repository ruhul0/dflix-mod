package com.dflixtv.app.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dflixtv.app.DflixApp
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dflixtv.app.data.BrowseFilter
import com.dflixtv.app.update.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark, so system bar icons must be light regardless of the device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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

private enum class Tab(val label: String) {
    Home("Home"), Shows("TV Shows"), Movies("Movies"), Anime("Anime"), MyList("My List"), Search("Search"), Settings("Settings")
}

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
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries
    val firstTab = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    LaunchedEffect(Unit) { if (isTv) runCatching { firstTab.requestFocus() } }

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = if (isTv) 72.dp else 52.dp
    val topInset = statusBar + headerHeight
    val tab = tabs[selected]
    val homeList = rememberLazyListState()
    // Like Netflix, the header is see-through over the billboard and turns solid once you scroll.
    val scrolled by remember { derivedStateOf { homeList.firstVisibleItemIndex > 0 || homeList.firstVisibleItemScrollOffset > 300 } }
    val solidHeader = tab != Tab.Home || scrolled

    Box(Modifier.fillMaxSize()) {
        // Content draws under the header; Home's billboard runs edge to edge behind it.
        // The header overlays the page, so D-pad Down/Up are routed explicitly between the two.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = if (tab == Tab.Home) 0.dp else topInset)
                .focusRequester(content)
                .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Up) firstTab.requestFocus() } }
                .focusGroup()
        ) {
            when (tab) {
                Tab.Home -> HomeScreen(open, topInset, homeList)
                Tab.Shows -> BrowseScreen("shows", BrowseFilter(kind = "tv"), open)
                Tab.Movies -> BrowseScreen("movies", BrowseFilter(kind = "movie"), open)
                Tab.Anime -> BrowseScreen("anime", BrowseFilter(kind = "all", anime = true), open)
                Tab.MyList -> MyListScreen(open)
                Tab.Search -> SearchScreen(open)
                Tab.Settings -> SettingsScreen()
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (solidHeader) SolidColor(Background.copy(alpha = 0.96f))
                    else Brush.verticalGradient(0f to Color(0xE6000000), 1f to Color.Transparent)
                )
                .padding(top = statusBar)
                .height(headerHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DFLIX",
                color = Brand,
                fontWeight = FontWeight.Black,
                fontSize = if (isTv) 30.sp else 24.sp,
                letterSpacing = (-1).sp,
                modifier = Modifier.padding(start = gutter(), end = 12.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(end = gutter()),
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(tabs) { index, t ->
                    NavItem(
                        t.label,
                        selected = index == selected,
                        onClick = { selected = index },
                        modifier = (if (index == 0) Modifier.focusRequester(firstTab) else Modifier)
                            .focusProperties { down = content },
                    )
                }
            }
        }
    }
}

@Composable
private fun MyListScreen(open: (Long) -> Unit) {
    var titles by remember { mutableStateOf(DflixApp.store.myList()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { titles = DflixApp.store.myList() }
    if (titles.isEmpty()) {
        CenterMessage("Add shows and movies with + My List to find them here")
        return
    }
    val width = if (LocalIsTv.current) 150.dp else 104.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(width),
        contentPadding = PaddingValues(start = gutter(), end = gutter(), top = 8.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(titles, key = { it.id }) { t -> PosterCard(t, onClick = { open(t.id) }, width = width) }
    }
}
