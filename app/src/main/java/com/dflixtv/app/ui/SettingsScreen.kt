package com.dflixtv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.dflixtv.app.BuildConfig
import com.dflixtv.app.DflixApp
import com.dflixtv.app.subs.Language
import com.dflixtv.app.update.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val store = DflixApp.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var audioLanguage by remember { mutableStateOf(store.audioLanguage) }
    var osKey by remember { mutableStateOf(store.openSubtitlesApiKey) }
    var subdlKey by remember { mutableStateOf(store.subdlApiKey) }
    var aiKey by remember { mutableStateOf(store.openRouterApiKey) }
    var phoneEntry by remember { mutableStateOf(false) }
    if (phoneEntry) {
        PhoneKeyEntryDialog(onDismiss = {
            phoneEntry = false
            // Keys may have arrived from the phone.
            aiKey = store.openRouterApiKey
            osKey = store.openSubtitlesApiKey
            subdlKey = store.subdlApiKey
        })
    }
    var autoplay by remember { mutableStateOf(store.autoPlayNext) }
    var autoSubs by remember { mutableStateOf(store.autoSubtitles) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Header("Audio language", "The player picks the best-quality track in this language. Original uses the title's own language.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    Pill("Original", selected = audioLanguage == "original", onClick = { audioLanguage = "original"; store.audioLanguage = "original" })
                }
                items(Language.ALL.filterNot { '-' in it.code2 }) { lang ->
                    Pill(lang.name, selected = audioLanguage == lang.code2, onClick = { audioLanguage = lang.code2; store.audioLanguage = lang.code2 })
                }
            }
        }
        item {
            Header(
                "Subtitle providers",
                "OpenSubtitles.org works without a key. Add free keys to also search OpenSubtitles.com " +
                    "(opensubtitles.com/consumers) and SubDL (subdl.com/panel/api).",
            )
            // Comes before the fields: focusing a field opens the keyboard, which is slow with a remote.
            NetflixButton(
                "Enter keys with your phone",
                onClick = { phoneEntry = true },
                icon = "📱",
                modifier = Modifier.padding(bottom = 16.dp),
            )
            KeyField("OpenSubtitles.com API key", osKey) { osKey = it; store.openSubtitlesApiKey = it }
            KeyField("SubDL API key", subdlKey) { subdlKey = it; store.subdlApiKey = it }
        }
        item {
            Header(
                "AI suggestions",
                "Top Picks for You on Home and Ask AI in Search use free AI models from OpenRouter. " +
                    "Add your own free key (openrouter.ai/keys) if the built-in one is busy.",
            )
            KeyField("OpenRouter API key", aiKey) { aiKey = it; store.openRouterApiKey = it }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Header("Auto-load subtitles", "Turns on English subtitles when a video starts, finding them online if needed.")
                }
                Switch(
                    checked = autoSubs,
                    onCheckedChange = { autoSubs = it; store.autoSubtitles = it },
                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand),
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Header("Autoplay next episode", null) }
                Switch(
                    checked = autoplay,
                    onCheckedChange = { autoplay = it; store.autoPlayNext = it },
                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand),
                )
            }
        }
        item {
            if (!BuildConfig.SELF_UPDATE) {
                Header("App version ${BuildConfig.VERSION_NAME}", "Updates come from Google Play.")
                return@item
            }
            Header("App version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "Updates come from GitHub releases.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(modifier = Modifier.focusRing(RoundedCornerShape(50)), onClick = {
                    updateStatus = "Checking…"
                    scope.launch { updateStatus = Updater.checkNow(context) }
                }) { Text("Check for updates") }
                updateStatus?.let { Text(it, Modifier.padding(start = 16.dp), color = Color.White.copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 10.dp))
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        if (LocalIsTv.current) {
            // A text field would open the keyboard as soon as the remote passes over it, so on TV
            // the value shows as a button and OK opens an edit dialog.
            var editing by remember { mutableStateOf(false) }
            Text(
                value.ifEmpty { "Not set" }.let { if (it.length > 24) it.take(10) + "…" + it.takeLast(6) else it },
                color = if (value.isEmpty()) Dim else Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .focusRing(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1C1C22), RoundedCornerShape(8.dp))
                    .clickable { editing = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (editing) TextEntryDialog(label, value, onDismiss = { editing = false }) { onChange(it); editing = false }
        } else {
            SearchTextField(value, onChange, "Not set", Modifier.fillMaxWidth())
        }
    }
}

/** Edits one text value in a dialog; the keyboard opens straight away. */
@Composable
private fun TextEntryDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Background,
        title = { Text(title, color = Color.White) },
        text = {
            SearchTextField(text, { text = it }, "Type here", Modifier.fillMaxWidth().focusRequester(focus), onDone = { onSave(text.trim()) })
        },
        confirmButton = { NetflixButton("Save", onClick = { onSave(text.trim()) }, primary = true) },
        dismissButton = { NetflixButton("Cancel", onClick = onDismiss) },
    )
}
