package com.dflixtv.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.TitleSummary
import com.dflixtv.app.player.PlayerActivity

// Netflix-like palette: near-black page, red brand, grey secondary buttons.
val Brand = Color(0xFFE50914)
val Background = Color(0xFF141414)
val CardColor = Color(0xFF232323)
val SecondaryButton = Color(0x99727272)
val Dim = Color(0xFFB3B3B3)

/** True on Android TV / Google TV, where navigation is by D-pad and focus must be visible. */
val LocalIsTv = staticCompositionLocalOf { false }

fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/** Horizontal page gutter: roomy on TV, tighter on phones. */
@Composable
fun gutter(): Dp = if (LocalIsTv.current) 56.dp else 12.dp

/**
 * Focus highlight for D-pad / arrow-key navigation: scales up and draws a white ring.
 * Must come before the clickable/focusable modifier it should observe.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(4.dp), onFocused: () -> Unit = {}): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "focusScale")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        }
}

/** A clickable surface that works with touch, mouse, D-pad and keyboard (Enter). */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .focusRing(shape, onFocused)
            .clip(shape)
            .background(CardColor)
            .clickable(onClick = onClick),
        content = content,
    )
}

/** Netflix-style button: white primary ("Play") or translucent grey secondary ("More info"). */
@Composable
fun NetflixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: String? = null,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .focusRing(shape)
            .clip(shape)
            .background(if (primary) Color.White else SecondaryButton)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Text(it, color = if (primary) Color.Black else Color.White, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text(
            text,
            color = if (primary) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

/** Text navigation item, Netflix-style: bold white when selected, grey otherwise. */
@Composable
fun NavItem(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        color = if (selected) Color.White else Dim,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = if (LocalIsTv.current) 18.sp else 15.sp,
        modifier = modifier
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Outlined chip used for filters and seasons. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.Black else Color.White,
        )
    }
}

@Composable
fun PillRow(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter(), vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Poster tile without caption, like Netflix's rows. */
@Composable
fun PosterCard(
    title: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = if (LocalIsTv.current) 150.dp else 104.dp,
    onFocused: () -> Unit = {},
) {
    FocusCard(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.width(width).aspectRatio(2f / 3f),
    ) {
        AsyncImage(
            model = title.posterUrl,
            contentDescription = title.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (title.posterUrl == null) {
            Text(title.title, Modifier.align(Alignment.Center).padding(8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Landscape backdrop tile with the title over a bottom fade (Netflix TV rows). */
@Composable
fun LandscapeCard(
    title: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 280.dp,
    onFocused: () -> Unit = {},
) {
    FocusCard(onClick = onClick, onFocused = onFocused, modifier = modifier.width(width).aspectRatio(16f / 9f)) {
        AsyncImage(
            model = title.backdropUrl ?: title.posterUrl,
            contentDescription = title.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
        Text(
            title.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
    }
}

@Composable
fun TitleRow(
    label: String,
    titles: List<TitleSummary>,
    onClick: (TitleSummary) -> Unit,
    onFocused: (TitleSummary) -> Unit = {},
    landscape: Boolean = false,
) {
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionLabel(label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 12.dp else 8.dp),
        ) {
            items(titles, key = { it.id }) { t ->
                if (landscape) LandscapeCard(t, onClick = { onClick(t) }, onFocused = { onFocused(t) })
                else PosterCard(t, onClick = { onClick(t) }, onFocused = { onFocused(t) })
            }
        }
    }
}

/** "Top 10" row: a huge outlined rank number tucked behind each poster. */
@Composable
fun Top10Row(
    label: String,
    titles: List<TitleSummary>,
    onClick: (TitleSummary) -> Unit,
    onFocused: (TitleSummary) -> Unit = {},
) {
    val tv = LocalIsTv.current
    val posterWidth = if (tv) 140.dp else 96.dp
    val numberSize = if (tv) 200.sp else 140.sp
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionLabel(label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(titles.take(10), key = { _, t -> t.id }) { index, t ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${index + 1}",
                        style = TextStyle(
                            fontSize = numberSize,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF8C8C8C),
                            drawStyle = Stroke(width = 5f),
                        ),
                        modifier = Modifier.offset(x = 12.dp, y = if (tv) 40.dp else 28.dp),
                    )
                    PosterCard(t, onClick = { onClick(t) }, onFocused = { onFocused(t) }, width = posterWidth)
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = if (LocalIsTv.current) 20.sp else 17.sp,
        color = Color.White,
        modifier = Modifier.padding(start = gutter(), bottom = 2.dp),
    )
}

/** Genres separated by dots: "Crime • Drama • Mystery". */
@Composable
fun GenreLine(genres: List<String>, modifier: Modifier = Modifier) {
    if (genres.isEmpty()) return
    Text(
        genres.take(4).joinToString("  •  "),
        color = Color.White,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Backdrop that fades into the page background, used behind headers. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, fadeLeft: Boolean = true) {
    Box(modifier) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (fadeLeft) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Background.copy(alpha = 0.9f), 0.55f to Color.Transparent)
                )
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.65f to Color.Transparent, 1f to Background)
            )
        )
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Dim)
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Brand))
    }
}

/** Small outlined badge for ratings and quality ("TV-MA", "HD"). */
@Composable
fun Badge(text: String) {
    Text(
        text,
        color = Dim,
        fontSize = 12.sp,
        modifier = Modifier
            .border(1.dp, Dim, RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Starts playback for a title straight from a row or hero: resumes where the viewer left off,
 * otherwise plays the first file (or S1E1).
 */
suspend fun playTitle(context: Context, titleId: Long) {
    DflixApp.store.continueWatching().firstOrNull { it.titleId == titleId }?.let {
        PlayerActivity.start(context, titleId, it.fileId)
        return
    }
    val details = runCatching { DflixApi.titlePage(titleId).first }.getOrNull()
    val first = details?.seasons?.values?.firstOrNull()?.firstOrNull()
    if (first != null) PlayerActivity.start(context, titleId, first.id)
    else Toast.makeText(context, "Nothing to play", Toast.LENGTH_SHORT).show()
}
