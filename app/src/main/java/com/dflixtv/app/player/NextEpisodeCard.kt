package com.dflixtv.app.player

import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Netflix-style "Next episode" card: appears in the last [LEAD_MS] of an episode and counts down
 * from [COUNTDOWN_S]; the countdown pauses with the video.
 */
class NextEpisodeCard(private val card: View, private val title: TextView, private val playNow: Button) {
    private var countdown: Int? = null

    val isShowing: Boolean get() = countdown != null

    /**
     * Called once a second. [remainingMs] is null when no card should show (no next episode,
     * autoplay off, dismissed…). Returns true when the countdown ran out: start the next episode.
     */
    fun tick(remainingMs: Long?, playing: Boolean, nextLabel: () -> String): Boolean {
        if (remainingMs == null || remainingMs > LEAD_MS) {
            hide()
            return false
        }
        val current = countdown
        if (current == null) {
            title.text = nextLabel()
            countdown = COUNTDOWN_S
            card.visibility = View.VISIBLE
            playNow.requestFocus()
        } else if (playing) {
            if (current <= 1) {
                hide()
                return true
            }
            countdown = current - 1
        }
        playNow.text = "Play now ($countdown)"
        return false
    }

    /** Returns true if the card was showing. */
    fun hide(): Boolean {
        if (countdown == null) return false
        countdown = null
        card.visibility = View.GONE
        return true
    }

    companion object {
        const val LEAD_MS = 30_000L
        const val COUNTDOWN_S = 10
    }
}
