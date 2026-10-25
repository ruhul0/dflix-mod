package com.dflixtv.app.subs

import com.dflixtv.app.DflixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles movie hash: file size plus the 64-bit little-endian word sums of the first and
 * last 64 KiB. Computed over HTTP with two range requests, so the video is never downloaded.
 */
object OsHash {
    private const val CHUNK = 65536L

    suspend fun compute(url: String, size: Long): String? = withContext(Dispatchers.IO) {
        if (size < CHUNK * 2) return@withContext null
        runCatching {
            val head = range(url, 0, CHUNK - 1)
            val tail = range(url, size - CHUNK, size - 1)
            var hash = size
            hash += sum(head)
            hash += sum(tail)
            "%016x".format(hash)
        }.getOrNull()
    }

    private fun range(url: String, from: Long, to: Long): ByteArray {
        val request = Request.Builder().url(url).header("Range", "bytes=$from-$to").build()
        DflixApp.http.newCall(request).execute().use { response ->
            check(response.code == 206) { "Range not supported" }
            val bytes = response.body.bytes()
            check(bytes.size.toLong() == CHUNK) { "Short read" }
            return bytes
        }
    }

    private fun sum(bytes: ByteArray): Long {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
        var total = 0L
        while (buffer.hasRemaining()) total += buffer.get()
        return total
    }
}
