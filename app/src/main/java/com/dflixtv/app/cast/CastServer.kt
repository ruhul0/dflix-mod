package com.dflixtv.app.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.DflixApi
import com.dflixtv.app.data.SyncData
import com.dflixtv.app.data.SyncMerge
import com.dflixtv.app.data.SyncState
import android.os.Handler
import android.os.Looper
import com.dflixtv.app.net.MiniHttpServer
import com.dflixtv.app.player.PlayerActivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** What the TV is playing, for the phone's remote. Written by the player, read by [CastServer]. */
object NowPlaying {
    @Volatile var status: CastStatus = CastStatus()
}

@Serializable
data class CastStatus(
    val active: Boolean = false,
    val titleId: Long = 0,
    val fileId: Long = 0,
    val title: String = "",
    val subtitle: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * Runs on the TV: announces "DFLIX on <TV>" on the local network (mDNS service [SERVICE_TYPE]) and
 * accepts play, remote-control and sync requests from the DFLIX phone app.
 *
 *   GET  /status                         → [CastStatus] JSON
 *   POST /play     title, file, [pos]    → starts that file on the TV
 *   POST /control  action=toggle|back|forward|stop
 *   POST /sync     data=[SyncData] JSON  → merges with the TV's and returns the result
 */
object CastServer {
    const val SERVICE_TYPE = "_dflixtv._tcp."
    private const val PREFERRED_PORT = 8611
    private const val TAG = "CastServer"
    private var server: MiniHttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val http = MiniHttpServer("cast", PREFERRED_PORT) { handle(app, it) }
        http.start()
        server = http
        Log.i(TAG, "listening on port ${http.port}")

        val info = NsdServiceInfo().apply {
            serviceName = "DFLIX on ${deviceName(app)}"
            serviceType = SERVICE_TYPE
            port = http.port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "register failed $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        runCatching {
            (app.getSystemService(Context.NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        }.onFailure { Log.w(TAG, "NSD unavailable", it) }
    }

    private fun handle(context: Context, request: MiniHttpServer.Request): MiniHttpServer.Response {
        val json = DflixApi.json
        return when (request.path) {
            "/status" -> MiniHttpServer.Response.json(json.encodeToString(NowPlaying.status))
            "/play" -> {
                val titleId = request.form["title"]?.toLongOrNull()
                val fileId = request.form["file"]?.toLongOrNull()
                if (request.method != "POST" || titleId == null || fileId == null) return MiniHttpServer.Response.json("""{"error":"bad request"}""", 400)
                // Android only lets an app open a screen while it is in front.
                if (!DflixApp.inForeground) return MiniHttpServer.Response.json("""{"error":"Open DFLIX on the TV first"}""", 409)
                PlayerActivity.start(context, titleId, fileId, startMs = request.form["pos"]?.toLongOrNull())
                MiniHttpServer.Response.json("""{"ok":true}""")
            }
            "/sync" -> {
                val remote = request.form["data"]?.let { runCatching { json.decodeFromString<SyncData>(it) }.getOrNull() }
                    ?: return MiniHttpServer.Response.json("""{"error":"bad request"}""", 400)
                val store = DflixApp.store
                val merged = SyncMerge.merge(store.syncData(), remote)
                store.applySync(merged)
                Handler(Looper.getMainLooper()).post { SyncState.changed() }
                MiniHttpServer.Response.json(json.encodeToString(merged))
            }
            "/control" -> {
                if (request.method != "POST") return MiniHttpServer.Response.json("""{"error":"bad request"}""", 400)
                val action = when (request.form["action"]) {
                    "toggle" -> PlayerActivity.CONTROL_TOGGLE
                    "back" -> PlayerActivity.CONTROL_BACK
                    "forward" -> PlayerActivity.CONTROL_FORWARD
                    "stop" -> PlayerActivity.CONTROL_STOP
                    else -> return MiniHttpServer.Response.json("""{"error":"unknown action"}""", 400)
                }
                context.sendBroadcast(PlayerActivity.controlIntent(context, action))
                MiniHttpServer.Response.json("""{"ok":true}""")
            }
            else -> MiniHttpServer.Response.json("""{"error":"not found"}""", 404)
        }
    }

    private fun deviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
