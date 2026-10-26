package com.dflixtv.app.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.ui.SubtitleView
import java.io.File

/**
 * Draws a downloaded subtitle file over the video ourselves, in step with [position]. ExoPlayer can
 * only add a subtitle file by reloading the whole video, which rebuffers; this switches instantly.
 */
@UnstableApi
class SideSubtitles(private val view: SubtitleView, private val position: () -> Long) {
    private class Timed(val startMs: Long, val endMs: Long, val cues: List<Cue>)

    private var entries: List<Timed> = emptyList()
    private var shown: List<Cue> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 100)
        }
    }

    val isActive: Boolean get() = entries.isNotEmpty()

    /** Parses [file] and starts drawing it; returns false if it couldn't be read. */
    fun load(file: File): Boolean {
        val parser: SubtitleParser = when (file.extension.lowercase()) {
            "vtt" -> WebvttParser()
            "ass", "ssa" -> SsaParser()
            else -> SubripParser()
        }
        val parsed = mutableListOf<Timed>()
        val ok = runCatching {
            parser.parse(file.readBytes(), SubtitleParser.OutputOptions.allCues(), Consumer<CuesWithTiming> { c ->
                if (c.cues.isNotEmpty()) parsed += Timed(c.startTimeUs / 1000, c.endTimeUs / 1000, c.cues)
            })
        }.isSuccess
        if (!ok || parsed.isEmpty()) return false
        entries = parsed.sortedBy { it.startMs }
        shown = emptyList()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return true
    }

    fun clear() {
        handler.removeCallbacks(tick)
        entries = emptyList()
        shown = emptyList()
        view.setCues(emptyList())
    }

    private fun render() {
        val now = position()
        val cues = entries.asSequence()
            .takeWhile { it.startMs <= now }
            .filter { now < it.endMs }
            .flatMap { it.cues }
            .toList()
        // Only touch the view when the text changes.
        if (cues != shown) {
            shown = cues
            view.setCues(cues)
        }
    }
}
