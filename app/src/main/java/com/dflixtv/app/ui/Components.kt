package com.dflixtv.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dflixtv.app.data.TitleSummary

val Brand = Color(0xFFE50914)

@Composable
fun PosterCard(
    title: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    onFocused: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(MaterialTheme.shapes.small),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1C1C22))) {
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
                            .background(Color(0xCC000000), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
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
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
    )
}

/** Full-bleed backdrop that fades into the background, used behind headers. */
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
                Brush.horizontalGradient(0f to Color(0xFF0B0B0F), 0.6f to Color(0x990B0B0F), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xFF0B0B0F))
            )
        )
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Brand))
    }
}
