package com.dflixtv.app

import android.app.Application
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
        store = Store(this)
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
