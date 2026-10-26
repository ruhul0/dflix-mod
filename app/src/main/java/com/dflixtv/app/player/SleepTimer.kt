package com.dflixtv.app.player

import android.os.SystemClock

/** Stops playback after a number of minutes, or at the end of the current episode. */
class SleepTimer {
    private var atElapsed: Long? = null
    private var minutes: Int? = null

    var afterEpisode = false
        private set

    val isOff: Boolean get() = atElapsed == null && !afterEpisode

    /** Minutes left, rounded up, while a minute timer runs. */
    val minutesLeft: Long? get() = atElapsed?.let { ((it - SystemClock.elapsedRealtime()) / 60_000).coerceAtLeast(0) + 1 }

    fun isRunning(forMinutes: Int) = atElapsed != null && minutes == forMinutes

    /** Sets the timer ([minutes] null and [endOfEpisode] false turns it off); returns a confirmation. */
    fun set(minutes: Int?, endOfEpisode: Boolean): String {
        this.minutes = minutes
        atElapsed = minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        afterEpisode = endOfEpisode
        return when {
            endOfEpisode -> "Playback stops at the end of this episode"
            minutes != null -> "Playback stops in $minutes minutes"
            else -> "Sleep timer off"
        }
    }

    /** True once, when a minute timer has run out. */
    fun consumeIfDue(): Boolean {
        val at = atElapsed ?: return false
        if (SystemClock.elapsedRealtime() < at) return false
        atElapsed = null
        minutes = null
        return true
    }
}
