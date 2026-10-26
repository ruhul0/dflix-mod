package com.dflixtv.app.diag

import android.content.Context
import android.os.Build
import com.dflixtv.app.BuildConfig
import com.dflixtv.app.DflixApp
import com.dflixtv.app.cast.CastClient
import com.dflixtv.app.cast.CastServer
import com.dflixtv.app.data.AiSuggest
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.ui.isTv
import com.dflixtv.app.ui.localAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/** One line of the Diagnostics report: ok true/false, or null for information only. */
data class Check(val name: String, val ok: Boolean?, val detail: String)

/** Quick health checks for Settings > Diagnostics, to tell why something isn't working. */
object Diagnostics {

    fun device(): String =
        "DFLIX ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    suspend fun run(context: Context): List<Check> = coroutineScope {
        listOf(
            async { dflix() },
            async { openSubtitles() },
            async { ai() },
        ).awaitAll() + link(context)
    }

    private suspend fun dflix(): Check = timed("dflix.live") {
        val results = DflixApi.search("the")
        "search answered with ${results.size} titles"
    }

    private suspend fun openSubtitles(): Check = timed("Subtitles (OpenSubtitles.org)") {
        get("https://rest.opensubtitles.org/search/query-test/sublanguageid-eng", mapOf("User-Agent" to "TemporaryUserAgent"))
        "search reachable"
    }

    private suspend fun ai(): Check {
        if (!AiSuggest.isAvailable) return Check("AI suggestions", null, "no OpenRouter key; Top Picks and Ask AI are off")
        val own = DflixApp.store.openRouterApiKey.isNotBlank()
        val key = if (own) DflixApp.store.openRouterApiKey else BuildConfig.OPENROUTER_KEY
        return timed("AI suggestions (${if (own) "your key" else "built-in key"})") {
            get("https://openrouter.ai/api/v1/key", mapOf("Authorization" to "Bearer $key"))
            "key accepted"
        }
    }

    /** The phone ↔ TV link: what this device offers or can see on the Wi-Fi. */
    private fun link(context: Context): Check {
        val address = localAddress(context)
        return when {
            address == null -> Check("Phone ↔ TV link", false, "not connected to Wi-Fi or Ethernet")
            context.isTv() -> {
                val port = CastServer.port
                if (port == null) Check("Phone ↔ TV link", false, "not running")
                else Check("Phone ↔ TV link", true, "phones can reach this TV at $address:$port")
            }
            else -> {
                val tvs = CastClient.targets
                if (tvs.isEmpty()) Check("Phone ↔ TV link", false, "no DFLIX TV found on this Wi-Fi ($address). Open DFLIX on the TV; guest networks and \"AP isolation\" block this.")
                else Check("Phone ↔ TV link", true, "found ${tvs.joinToString { "${it.name} (${it.host})" }}")
            }
        }
    }

    private suspend fun timed(name: String, block: suspend () -> String): Check {
        val start = System.currentTimeMillis()
        return runCatching { block() }.fold(
            onSuccess = { Check(name, true, "$it · ${System.currentTimeMillis() - start} ms") },
            onFailure = { Check(name, false, it.message ?: it.javaClass.simpleName) },
        )
    }

    private suspend fun get(url: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        DflixApp.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        }
    }

    /**
     * A pre-filled "New issue" link on this app's GitHub repo. Nothing is posted until the viewer
     * presses Submit, so the app needs no GitHub credentials. [maxBody] keeps it short enough for
     * a QR code on TV.
     */
    fun issueUrl(checks: List<Check>, maxBody: Int): String {
        val crash = CrashLog.crashes().firstOrNull()
        val title = crash?.lines()?.drop(2)?.firstOrNull { it.isNotBlank() }?.trim()?.take(90)?.let { "Crash: $it" }
            ?: "Bug report"
        val body = buildString {
            appendLine("**What happened?** (please describe)")
            appendLine()
            appendLine("```")
            append(report(checks).take(maxBody))
            appendLine("```")
        }
        return "https://github.com/${BuildConfig.UPDATE_REPO}/issues/new?title=${enc(title)}&body=${enc(body)}"
    }

    private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** Plain-text report for sending to a phone or chat. */
    fun report(checks: List<Check>): String = buildString {
        appendLine(device())
        checks.forEach { appendLine("${if (it.ok == true) "OK  " else if (it.ok == false) "FAIL" else "INFO"} ${it.name}: ${it.detail}") }
        val crashes = CrashLog.crashes()
        appendLine()
        if (crashes.isEmpty()) appendLine("No crashes recorded.") else crashes.forEach { appendLine(it).appendLine() }
    }
}
