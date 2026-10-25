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
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
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
    var language by remember { mutableStateOf(store.subtitleLanguage) }
    var osKey by remember { mutableStateOf(store.openSubtitlesApiKey) }
    var subdlKey by remember { mutableStateOf(store.subdlApiKey) }
    var autoplay by remember { mutableStateOf(store.autoPlayNext) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Header("Subtitle language", "Used when searching subtitles online.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Language.ALL) { lang ->
                    FilterChip(selected = language == lang.code3, onClick = { language = lang.code3; store.subtitleLanguage = lang.code3 }) {
                        Text(lang.name)
                    }
                }
            }
        }
        item {
            Header(
                "Subtitle providers",
                "OpenSubtitles.org works without a key. Add free keys to also search OpenSubtitles.com " +
                    "(opensubtitles.com/consumers) and SubDL (subdl.com/panel/api).",
            )
            KeyField("OpenSubtitles.com API key", osKey) { osKey = it; store.openSubtitlesApiKey = it }
            KeyField("SubDL API key", subdlKey) { subdlKey = it; store.subdlApiKey = it }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Header("Autoplay next episode", null) }
                Switch(checked = autoplay, onCheckedChange = { autoplay = it; store.autoPlayNext = it })
            }
        }
        item {
            if (!BuildConfig.SELF_UPDATE) {
                Header("App version ${BuildConfig.VERSION_NAME}", "Updates come from Google Play.")
                return@item
            }
            Header("App version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "Updates come from GitHub releases.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
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
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(260.dp), style = MaterialTheme.typography.bodyMedium)
        SearchTextField(value, onChange, "Not set", Modifier.fillMaxWidth(0.6f))
    }
}
