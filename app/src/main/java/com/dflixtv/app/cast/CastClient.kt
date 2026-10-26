package com.dflixtv.app.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dflixtv.app.DflixApp
import com.dflixtv.app.data.DflixApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CastTarget(val name: String, val host: String, val port: Int) {
    val baseUrl get() = "http://$host:$port"
}

/**
 * Runs on phones: finds DFLIX TV apps on the local network and sends them titles to play and
 * remote-control commands. [targets] and [active] are Compose state for the UI.
 */
object CastClient {
    private const val TAG = "CastClient"

    var targets by mutableStateOf<List<CastTarget>>(emptyList())
        private set

    /** The TV we last sent something to; the remote controls this one. */
    var active by mutableStateOf<CastTarget?>(null)

    private var discovery: NsdManager.DiscoveryListener? = null
    private var appContext: Context? = null
    // Android resolves one service at a time; extra requests fail with FAILURE_ALREADY_ACTIVE.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val http by lazy { DflixApp.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build() }

    fun startDiscovery(context: Context) {
        if (discovery != null) return
        appContext = context.applicationContext
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed $errorCode")
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(resolveQueue) { resolveQueue.addLast(service) }
                resolveNext(nsd)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                targets = targets.filterNot { it.name == service.serviceName }
                if (active?.name == service.serviceName) active = null
            }
        }
        runCatching {
            nsd.discoverServices(CastServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
        }.onFailure { Log.w(TAG, "NSD unavailable", it) }
    }

    private fun resolveNext(nsd: NsdManager) {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            resolveQueue.removeFirstOrNull()?.also { resolving = true } ?: return
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                info.host?.hostAddress?.let { host ->
                    val target = CastTarget(info.serviceName, host, info.port)
                    targets = (targets.filterNot { it.name == target.name } + target).sortedBy { it.name }
                }
                done()
            }

            fun done() {
                synchronized(resolveQueue) { resolving = false }
                resolveNext(nsd)
            }
        })
    }

    /** A TV that stopped answering: forget it and look again, in case it came back on a new address. */
    private fun forget(target: CastTarget) {
        targets = targets.filterNot { it == target }
        if (active == target) active = null
        val context = appContext ?: return
        stopDiscovery(context)
        startDiscovery(context)
    }

    fun stopDiscovery(context: Context) {
        val listener = discovery ?: return
        discovery = null
        runCatching { (context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) }
    }

    suspend fun play(target: CastTarget, titleId: Long, fileId: Long, positionMs: Long?) {
        post(target, "/play", buildMap {
            put("title", titleId.toString())
            put("file", fileId.toString())
            positionMs?.takeIf { it > 0 }?.let { put("pos", it.toString()) }
        })
        active = target
    }

    suspend fun control(action: String) {
        val target = active ?: return
        post(target, "/control", mapOf("action" to action))
    }

    suspend fun status(): CastStatus? {
        val target = active ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("${target.baseUrl}/status").build()).execute().use { response ->
                    if (!response.isSuccessful) null else DflixApi.json.decodeFromString<CastStatus>(response.body.string())
                }
            }.getOrNull()
        }
    }

    private suspend fun post(target: CastTarget, path: String, form: Map<String, String>) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val call = http.newCall(Request.Builder().url(target.baseUrl + path).post(body).build())
        val reply = try {
            call.execute()
        } catch (e: IOException) {
            withContext(Dispatchers.Main) { forget(target) }
            throw IOException("TV not reachable. Searching again; try once more in a few seconds.", e)
        }
        reply.use { response ->
            if (!response.isSuccessful) {
                val message = runCatching {
                    DflixApi.json.parseToJsonElement(response.body.string()).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull()
                throw IOException(message ?: "TV answered HTTP ${response.code}")
            }
        }
    }
}
