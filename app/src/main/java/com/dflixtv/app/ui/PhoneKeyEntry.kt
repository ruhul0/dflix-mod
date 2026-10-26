package com.dflixtv.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dflixtv.app.DflixApp
import com.dflixtv.app.net.MiniHttpServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * Typing API keys with a TV remote is painful, so the TV briefly serves a one-page form on the
 * local network: scan the QR code with a phone, paste the keys, and they land in Settings.
 * The server only runs while the dialog is open and only accepts requests carrying a random token.
 */
private class KeyEntryServer(private val onSaved: (List<String>) -> Unit) {
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val http = MiniHttpServer("key-entry", handler = ::handle)
    val port: Int get() = http.port

    fun start() = http.start()

    fun stop() = http.stop()

    private fun handle(request: MiniHttpServer.Request): MiniHttpServer.Response {
        if (request.query["t"] != token) return MiniHttpServer.Response(403, page("This link has expired. Open it again from the TV."))
        if (request.method != "POST") return MiniHttpServer.Response(200, page(form = true))
        val store = DflixApp.store
        val saved = buildList {
            request.form["openrouter"]?.takeIf { it.isNotBlank() }?.let { store.openRouterApiKey = it; add("OpenRouter") }
            request.form["opensubtitles"]?.takeIf { it.isNotBlank() }?.let { store.openSubtitlesApiKey = it; add("OpenSubtitles.com") }
            request.form["subdl"]?.takeIf { it.isNotBlank() }?.let { store.subdlApiKey = it; add("SubDL") }
        }
        if (saved.isNotEmpty()) onSaved(saved)
        return MiniHttpServer.Response(
            200,
            page(if (saved.isEmpty()) "Nothing to save: all fields were empty." else "Saved on your TV: ${saved.joinToString(", ")}. You can close this page."),
        )
    }

    private fun page(message: String? = null, form: Boolean = false): String {
        val field = { name: String, label: String ->
            """<label>$label<input name="$name" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Leave empty to keep"></label>"""
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DFLIX TV keys</title><style>
body{margin:0;background:#141414;color:#fff;font:16px system-ui,sans-serif;padding:24px}
h1{color:#E50914;font-size:28px;margin:0 0 8px}p{color:#b3b3b3}
label{display:block;margin:18px 0 6px;color:#b3b3b3}
input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:12px;border-radius:6px;border:1px solid #444;background:#222;color:#fff;font-size:16px}
button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:6px;background:#E50914;color:#fff;font-size:17px;font-weight:700}
</style></head><body><h1>DFLIX</h1>
${message?.let { "<p>$it</p>" } ?: ""}
${if (form) """<p>Paste keys for your TV. Only filled fields are saved.</p>
<form method="post" action="/?t=$token">${field("openrouter", "OpenRouter API key (AI suggestions)")}${field("opensubtitles", "OpenSubtitles.com API key")}${field("subdl", "SubDL API key")}
<button type="submit">Save to TV</button></form>""" else ""}
</body></html>"""
    }
}

/** The TV's address on the local network, or null when it isn't connected. */
internal fun localAddress(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return null
    return cm.getLinkProperties(network)?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}

internal fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** QR code dialog for entering API keys from a phone; calls [onDismiss] when closed or after saving. */
@Composable
fun PhoneKeyEntryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val address = remember { localAddress(context) }
    val server = remember { address?.let { KeyEntryServer { saved -> status = "Saved: ${saved.joinToString(", ")}" } } }
    DisposableEffect(server) {
        server?.start()
        onDispose { server?.stop() }
    }
    val url = server?.let { "http://$address:${it.port}/?t=${it.token}" }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Row(
                Modifier.background(Background, RoundedCornerShape(12.dp)).padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (qr != null) {
                    Image(
                        qr.asImageBitmap(),
                        contentDescription = "QR code",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(260.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Text("Enter keys with your phone", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (url == null) {
                        Text("Connect the TV to Wi-Fi or Ethernet first.", color = Dim, fontSize = 16.sp)
                    } else {
                        Text("1. Connect your phone to the same Wi-Fi as the TV.", color = Dim, fontSize = 16.sp)
                        Text("2. Scan the code, or open:", color = Dim, fontSize = 16.sp)
                        Text(url, color = Color.White, fontSize = 15.sp)
                        Text("3. Paste your keys and tap Save.", color = Dim, fontSize = 16.sp)
                    }
                    status?.let { Text(it, color = Color(0xFF46D369), fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    NetflixButton(
                        if (status != null) "Done" else "Close",
                        onClick = onDismiss,
                        primary = status != null,
                        modifier = Modifier.padding(top = 8.dp).focusRequester(closeFocus),
                    )
                }
            }
        }
    }
}
