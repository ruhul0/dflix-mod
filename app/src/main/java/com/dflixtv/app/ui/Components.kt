package com.dflixtv.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dflixtv.app.data.TitleSummary

val Brand = Color(0xFFE50914)
val Background = Color(0xFF0B0B0F)
val CardColor = Color(0xFF1C1C22)

/** True on Android TV / Google TV, where navigation is by D-pad and focus must be visible. */
val LocalIsTv = staticCompositionLocalOf { false }

fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/** Horizontal page gutter: roomy on TV, tighter on phones. */
@Composable
fun gutter(): Dp = if (LocalIsTv.current) 48.dp else 16.dp

/**
 * Focus highlight for D-pad / arrow-key navigation: scales up and draws a white ring.
 * Must come before the clickable/focusable modifier it should observe.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(8.dp), onFocused: () -> Unit = {}): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "focusScale")
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
    shape: Shape = RoundedCornerShape(8.dp),
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

/** Pill used for top navigation and filters. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.Black else Color.White,
        )
    }
}

@Composable
fun PillRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter(), vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun PosterCard(
    title: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = if (LocalIsTv.current) 140.dp else 110.dp,
    onFocused: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        FocusCard(
            onClick = onClick,
            onFocused = onFocused,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
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
            title.quality?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            title.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            listOfNotNull(title.year?.toString(), if (title.isShow) "Series" else null).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

@Composable
fun TitleRow(
    label: String,
    titles: List<TitleSummary>,
    onClick: (TitleSummary) -> Unit,
    onFocused: (TitleSummary) -> Unit = {},
) {
    Column(Modifier.padding(bottom = 20.dp)) {
        SectionLabel(label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(titles, key = { it.id }) { t ->
                PosterCard(t, onClick = { onClick(t) }, onFocused = { onFocused(t) })
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = gutter(), bottom = 4.dp),
    )
}

/** Backdrop that fades into the page background, used behind headers. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Background, 0.6f to Background.copy(alpha = 0.6f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.4f to Color.Transparent, 1f to Background)
            )
        )
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Brand))
    }
}
