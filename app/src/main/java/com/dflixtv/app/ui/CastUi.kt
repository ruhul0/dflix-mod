package com.dflixtv.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dflixtv.app.cast.CastClient
import com.dflixtv.app.cast.CastStatus
import com.dflixtv.app.cast.CastTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Phone-side "Play on TV": what to send, which TV to send it to, and the remote sheet. */
object CastUi {
    data class Pending(val titleId: Long, val fileId: Long, val positionMs: Long?)

    /** Something waiting for the viewer to pick a TV. */
    var picking by mutableStateOf<Pending?>(null)
    /** Picking a TV only to open its remote. */
    var pickingForRemote by mutableStateOf(false)
    var showRemote by mutableStateOf(false)
    var sending by mutableStateOf<Pending?>(null)

    /** Sends [fileId] to the TV in use, or asks which TV when there are several. */
    fun request(titleId: Long, fileId: Long, positionMs: Long?) {
        val pending = Pending(titleId, fileId, positionMs)
        val targets = CastClient.targets
        val active = CastClient.active?.takeIf { it in targets }
        when {
            active != null -> sending = pending.also { CastClient.active = active }
            targets.size == 1 -> sending = pending.also { CastClient.active = targets.first() }
            targets.isNotEmpty() -> picking = pending
        }
    }

    /** The 📺 button in the header. */
    fun openRemote() {
        val targets = CastClient.targets
        when {
            CastClient.active in targets -> showRemote = true
            targets.size == 1 -> { CastClient.active = targets.first(); showRemote = true }
            targets.isNotEmpty() -> pickingForRemote = true
        }
    }
}

/** Draws the TV picker and the remote sheet; lives at the app root so it survives navigation. */
@Composable
fun CastOverlay() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    CastUi.sending?.let { pending ->
        LaunchedEffect(pending) {
            val target = CastClient.active
            if (target != null) {
                runCatching { CastClient.play(target, pending.titleId, pending.fileId, pending.positionMs) }
                    .onSuccess { CastUi.showRemote = true }
                    .onFailure { Toast.makeText(context, "Couldn't play on TV: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            CastUi.sending = null
        }
    }

    if (CastUi.picking != null || CastUi.pickingForRemote) {
        TvPicker(
            onPick = { target ->
                CastClient.active = target
                val pending = CastUi.picking
                CastUi.picking = null
                if (CastUi.pickingForRemote) {
                    CastUi.pickingForRemote = false
                    CastUi.showRemote = true
                }
                pending?.let { CastUi.sending = it }
            },
            onDismiss = { CastUi.picking = null; CastUi.pickingForRemote = false },
        )
    }

    if (CastUi.showRemote) {
        RemoteSheet(onCommand = { action ->
            scope.launch {
                runCatching { CastClient.control(action) }
                    .onFailure { Toast.makeText(context, "TV didn't respond: ${it.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }
}

@Composable
private fun TvPicker(onPick: (CastTarget) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on which TV?") },
        text = {
            Column {
                CastClient.targets.forEach { target ->
                    Text(
                        "📺  ${target.name}",
                        fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(target) }.padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoteSheet(onCommand: (String) -> Unit) {
    var status by remember { mutableStateOf<CastStatus?>(null) }
    var reachable by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            val latest = CastClient.status()
            reachable = latest != null
            if (latest != null) status = latest
            delay(1_000)
        }
    }
    ModalBottomSheet(onDismissRequest = { CastUi.showRemote = false }, containerColor = Background) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("📺  ${CastClient.active?.name ?: "TV"}", color = Dim, fontSize = 14.sp)
            val current = status
            when {
                !reachable -> Text("Can't reach the TV. Is DFLIX open on it, on the same Wi-Fi?", color = Color.White)
                current == null || !current.active -> Text("Nothing is playing on the TV.", color = Color.White)
                else -> {
                    Text(current.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current.subtitle?.let { Text(it, color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (current.durationMs > 0) {
                        ProgressBar((current.positionMs.toFloat() / current.durationMs).coerceIn(0f, 1f), Modifier.fillMaxWidth())
                        Text("${clock(current.positionMs)} / ${clock(current.durationMs)}", color = Dim, fontSize = 13.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NetflixButton("10", onClick = { onCommand("back") }, icon = "⏪")
                        NetflixButton(
                            if (current.playing) "Pause" else "Play",
                            onClick = { onCommand("toggle") },
                            primary = true,
                            icon = if (current.playing) "⏸" else "▶",
                        )
                        NetflixButton("10", onClick = { onCommand("forward") }, icon = "⏩")
                    }
                    NetflixButton("Stop on TV", onClick = { onCommand("stop") }, icon = "⏹", modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
