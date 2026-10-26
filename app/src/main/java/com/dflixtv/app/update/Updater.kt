package com.dflixtv.app.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.dflixtv.app.BuildConfig
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.DflixApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Self-update from GitHub Releases. CI publishes a release tagged `v1.0.<build>` with the APK
 * attached on every push to main; the trailing build number is compared with our versionCode.
 */
object Updater {
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    @Serializable
    private data class Release(val tag_name: String, val name: String? = null, val body: String? = null, val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(val name: String, val browser_download_url: String, val size: Long = 0)

    private data class Update(val versionCode: Int, val release: Release, val apk: Asset)

    suspend fun checkOnLaunch(context: Context) {
        if (!BuildConfig.SELF_UPDATE) return
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0) < CHECK_INTERVAL_MS) return
        prefs.edit { putLong("last_check", now) }
        val update = runCatching { findUpdate() }.getOrNull() ?: return
        (context as? Activity)?.let { prompt(it, update) }
    }

    /** Manual check from Settings; returns a status line. */
    suspend fun checkNow(context: Context): String {
        val update = try {
            findUpdate()
        } catch (e: Exception) {
            return "Check failed: ${e.message}"
        } ?: return "You have the latest version"
        (context as? Activity)?.let { prompt(it, update) }
        return "Update ${update.release.tag_name} available"
    }

    private suspend fun findUpdate(): Update? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val release = DflixApp.http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // no releases yet
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            DflixApi.json.decodeFromString<Release>(response.body.string())
        }
        val code = Regex("""(\d+)$""").find(release.tag_name)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
        if (code > BuildConfig.VERSION_CODE) Update(code, release, apk) else null
    }

    private fun prompt(activity: Activity, update: Update) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update available: ${update.release.tag_name}")
            .setMessage(update.release.body?.let(::unwrap)?.take(800)?.ifBlank { null } ?: "A new version of DFLIX TV is ready.")
            .setPositiveButton("Install") { _, _ -> download(activity, update) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Release notes come from commit messages wrapped at 72 columns; rejoin lines within paragraphs. */
    private fun unwrap(text: String): String =
        text.replace("\r\n", "\n").split(Regex("\n{2,}")).joinToString("\n\n") { it.trim().replace(Regex("\\s*\n\\s*"), " ") }

    private fun download(activity: Activity, update: Update) {
        val progress = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("Downloading update…")
            .setCancelable(false)
            .show()
        Thread {
            val result = runCatching {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, "dflixtv-${update.versionCode}.apk")
                DflixApp.http.newCall(Request.Builder().url(update.apk.browser_download_url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    file.outputStream().use { response.body.byteStream().copyTo(it) }
                }
                file
            }
            activity.runOnUiThread {
                progress.dismiss()
                result.onSuccess { install(activity, it) }
                    .onFailure { Toast.makeText(activity, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow installing apps from DFLIX TV, then use Settings > Check for updates.", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
