package com.dflixtv.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.dflixtv.app.cast.CastServer
import com.dflixtv.app.diag.CrashLog
import io.sentry.android.core.SentryAndroid
import com.dflixtv.app.ui.isTv
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.dflixtv.app.data.Store
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DflixApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Sentry first: CrashLog records a crash, then hands it on to Sentry's handler.
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "dflix-tv@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = if (BuildConfig.DEBUG) "debug" else BuildConfig.FLAVOR
            options.isSendDefaultPii = false
            options.setTag("device", if (isTv()) "tv" else "phone")
        }
        CrashLog.install(this)
        store = Store(this)
        // Tracks whether any screen is showing: Android only lets the TV open the player for a
        // phone's "Play on TV" while the app is in front.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (isTv()) CastServer.start(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()

    companion object {
        const val USER_AGENT = "DflixTV/1.0 (Android TV)"

        lateinit var instance: DflixApp
            private set
        lateinit var store: Store
            private set

        @Volatile private var startedActivities = 0
        val inForeground: Boolean get() = startedActivities > 0

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    // Some subtitle APIs require their own User-Agent, so only fill it in when unset.
                    if (request.header("User-Agent") != null) chain.proceed(request)
                    else chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build()
        }
    }
}
