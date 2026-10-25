package com.dflixtv.app.subs

import android.content.Context
import com.dflixtv.app.data.SavedSubtitle
import com.dflixtv.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class SubtitleRepository(private val context: Context, private val store: Store) {

    private val providers: List<SubtitleProvider> = listOf(OpenSubtitlesOrg(), OpenSubtitlesCom(store), SubDl(store))

    /** Searches every configured provider in parallel. Failures of one provider don't hide the others. */
    suspend fun search(query: SubtitleQuery): SearchOutcome = coroutineScope {
        val outcomes = providers.filter { it.isConfigured }.map { provider ->
            async { provider to runCatching { provider.search(query) } }
        }.awaitAll()
        SearchOutcome(
            results = outcomes.flatMap { it.second.getOrDefault(emptyList()) }
                .sortedWith(compareByDescending<SubtitleResult> { it.hashMatch }.thenByDescending { it.downloads }),
            errors = outcomes.mapNotNull { (p, r) -> r.exceptionOrNull()?.let { "${p.name}: ${it.message}" } },
        )
    }

    /** Downloads, converts to UTF-8 and stores the subtitle so it is loaded next time too. */
    suspend fun download(result: SubtitleResult, query: SubtitleQuery, fileId: Long): SavedSubtitle {
        val provider = providers.first { it.name == result.provider }
        val (name, bytes) = provider.download(result, query)
        return withContext(Dispatchers.IO) {
            val text = decode(bytes, result.encodingHint)
            val ext = name.substringAfterLast('.', "srt").lowercase().takeIf { it in SubtitleFormats.SUPPORTED } ?: "srt"
            val dir = File(context.filesDir, "subs/$fileId").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}.$ext")
            file.writeText(text, Charsets.UTF_8)
            val saved = SavedSubtitle(
                path = file.absolutePath,
                label = "${query.language.name} · ${name.take(60)}",
                language = Language.tagFor(result.languageCode),
            )
            store.addSavedSubtitle(fileId, saved)
            saved
        }
    }

    /** Rewrites the subtitle file with every timestamp moved by [offsetMs] (positive = later). */
    suspend fun shift(subtitle: SavedSubtitle, offsetMs: Long) = withContext(Dispatchers.IO) {
        val file = File(subtitle.path)
        val shifted = SubtitleShifter.shift(file.readText(), offsetMs, file.extension.lowercase())
        file.writeText(shifted)
    }

    private fun decode(bytes: ByteArray, hint: String?): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            val charset = hint?.takeUnless { it.equals("ascii", true) || it.equals("utf-8", true) }
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charset.forName("windows-1252")
            String(bytes, charset)
        }
        return text.removePrefix("﻿")
    }
}

data class SearchOutcome(val results: List<SubtitleResult>, val errors: List<String>)

object SubtitleShifter {
    private val srtTime = Regex("""(\d{1,2}):(\d{2}):(\d{2})([,.])(\d{3})""")
    private val assTime = Regex("""(\d):(\d{2}):(\d{2})\.(\d{2})""")

    fun shift(text: String, offsetMs: Long, ext: String): String = text.lineSequence().joinToString("\n") { line ->
        when {
            ext in setOf("ass", "ssa") && line.startsWith("Dialogue:") -> {
                // Only the start/end fields (2nd and 3rd) hold times.
                val parts = line.split(",", limit = 4).toMutableList()
                if (parts.size == 4) {
                    parts[1] = shiftAss(parts[1], offsetMs)
                    parts[2] = shiftAss(parts[2], offsetMs)
                }
                parts.joinToString(",")
            }
            "-->" in line -> srtTime.replace(line) { m ->
                val (h, mi, s, sep, ms) = m.destructured
                val total = (h.toLong() * 3600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong() + offsetMs)
                    .coerceAtLeast(0)
                "%02d:%02d:%02d%s%03d".format(total / 3600_000, total / 60_000 % 60, total / 1000 % 60, sep, total % 1000)
            }
            else -> line
        }
    }

    private fun shiftAss(field: String, offsetMs: Long): String = assTime.replace(field) { m ->
        val (h, mi, s, cs) = m.destructured
        val total = (h.toLong() * 3600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + cs.toLong() * 10 + offsetMs)
            .coerceAtLeast(0)
        "%d:%02d:%02d.%02d".format(total / 3600_000, total / 60_000 % 60, total / 1000 % 60, total % 1000 / 10)
    }
}
