package com.dflixtv.app.ui

import android.content.Intent
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.dflixtv.app.diag.Check
import com.dflixtv.app.diag.CrashLog
import com.dflixtv.app.diag.Diagnostics
import com.dflixtv.app.net.MiniHttpServer
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Settings > Diagnostics: health checks, the last crash, and a way to send both to a phone. */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Check>?>(null) }
    var running by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf(CrashLog.lastSummary()) }
    var sharing by remember { mutableStateOf<String?>(null) }
    var reportUrl by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics", color = Color.White, fontSize = if (isTv) 20.sp else 17.sp, fontWeight = FontWeight.Medium)
        Text(Diagnostics.device(), color = Dim, fontSize = 13.sp)
        Text(
            lastCrash?.let { "Last crash: $it" } ?: "No crashes recorded.",
            color = if (lastCrash != null) Color(0xFFFF6B6B) else Dim,
            fontSize = 14.sp,
        )
        checks?.forEach { check ->
            val mark = when (check.ok) {
                true -> "✓"
                false -> "✗"
                null -> "i"
            }
            val color = when (check.ok) {
                true -> Color(0xFF46D369)
                false -> Color(0xFFFF6B6B)
                null -> Dim
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(mark, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${check.name}: ${check.detail}", color = Color.White, fontSize = 14.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            NetflixButton(if (running) "Checking…" else "Run checks", onClick = {
                if (running) return@NetflixButton
                running = true
                scope.launch {
                    checks = Diagnostics.run(context)
                    running = false
                }
            }, icon = "🩺")
            NetflixButton("Send report", onClick = {
                scope.launch {
                    val report = Diagnostics.report(checks ?: Diagnostics.run(context).also { checks = it })
                    if (isTv) {
                        sharing = report
                    } else {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                                "Send DFLIX report",
                            )
                        )
                    }
                }
            }, icon = "📤")
            NetflixButton("Report bug on GitHub", onClick = {
                scope.launch {
                    val results = checks ?: Diagnostics.run(context).also { checks = it }
                    if (isTv) {
                        // A QR code only holds so much: the TV sends a shorter report.
                        reportUrl = Diagnostics.issueUrl(results, maxBody = 600)
                    } else {
                        val url = Diagnostics.issueUrl(results, maxBody = 6_000)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    }
                }
            }, icon = "🐞")
            if (lastCrash != null) {
                NetflixButton("Clear crash log", onClick = { CrashLog.clear(); lastCrash = null })
            }
        }
    }
    sharing?.let { text -> ShareTextDialog("Send report to your phone", text) { sharing = null } }
    reportUrl?.let { url -> IssueQrDialog(url) { reportUrl = null } }
}

/** TV: a QR code of the pre-filled GitHub issue, to open and submit on a phone. */
@Composable
private fun IssueQrDialog(url: String, onDismiss: () -> Unit) {
    val qr = remember(url) { qrBitmap(url, 640) }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Row(
                Modifier.background(Background, RoundedCornerShape(12.dp)).padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    qr.asImageBitmap(),
                    contentDescription = "QR code",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(300.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Report bug on GitHub", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("1. Scan with your phone (any network).", color = Dim, fontSize = 16.sp)
                    Text("2. Sign in to GitHub, add what happened, tap Submit.", color = Dim, fontSize = 16.sp)
                    Text("The report holds the app version, device, checks and the last crash.", color = Dim, fontSize = 14.sp)
                    NetflixButton("Close", onClick = onDismiss, modifier = Modifier.padding(top = 8.dp).focusRequester(close))
                }
            }
        }
    }
}

/**
 * Shows [text] to a phone on the same Wi-Fi: the TV serves it once-off at a tokened URL shown as a
 * QR code, while the dialog is open.
 */
@Composable
private fun ShareTextDialog(title: String, text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val token = remember { ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } }
    val address = remember { localAddress(context) }
    val server = remember {
        address?.let {
            MiniHttpServer("share") { request ->
                if (request.query["t"] != token) MiniHttpServer.Response(403, "This link has expired.", "text/plain; charset=utf-8")
                else MiniHttpServer.Response(200, text, "text/plain; charset=utf-8")
            }
        }
    }
    DisposableEffect(server) {
        server?.start()
        onDispose { server?.stop() }
    }
    val url = server?.let { "http://$address:${it.port}/?t=$token" }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Row(
                Modifier.background(Background, RoundedCornerShape(12.dp)).padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                qr?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "QR code",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (url == null) {
                        Text("Connect the TV to Wi-Fi or Ethernet first.", color = Dim, fontSize = 16.sp)
                    } else {
                        Text("Scan with your phone on the same Wi-Fi, or open:", color = Dim, fontSize = 16.sp)
                        Text(url, color = Color.White, fontSize = 15.sp)
                        Text("Then copy the text and paste it wherever you need.", color = Dim, fontSize = 16.sp)
                    }
                    NetflixButton("Close", onClick = onDismiss, modifier = Modifier.padding(top = 8.dp).focusRequester(close))
                }
            }
        }
    }
}
