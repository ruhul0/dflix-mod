package com.dflixtv.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

class PhoneGestures(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val player: Player,
    private val hint: TextView,
) {
    private var hideHint: Runnable? = null

    /**
     * YouTube-style touch controls on phones: double-tap a side to skip 10 s, drag up/down on the
     * left for brightness and on the right for volume. Taps on the player's own buttons never get
     * here, because PlayerView only passes on touches its children didn't consume.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var adjusting: String? = null // "brightness" or "volume" while a vertical drag is in progress
        var startLevel = 0f

        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                adjusting = null
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < playerView.width / 2f) {
                    player.seekBack()
                    showHint("« 10s")
                } else {
                    player.seekForward()
                    showHint("10s »")
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val dy = start.y - e2.y
                if (adjusting == null) {
                    // Only clearly vertical drags adjust; anything else is left alone.
                    if (kotlin.math.abs(dy) < 24 || kotlin.math.abs(dy) < kotlin.math.abs(e2.x - start.x)) return false
                    adjusting = if (start.x < playerView.width / 2f) "brightness" else "volume"
                    startLevel = if (adjusting == "brightness") currentBrightness()
                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                }
                // A full-height drag covers the whole range.
                val level = (startLevel + dy / playerView.height).coerceIn(0f, 1f)
                if (adjusting == "brightness") {
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                    showHint("☀ ${(level * 100).toInt()}%")
                } else {
                    val volume = (level * maxVolume).roundToInt()
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                    showHint("🔊 ${volume * 100 / maxVolume}%")
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun currentBrightness(): Float =
        activity.window.attributes.screenBrightness.takeIf { it >= 0 }
            ?: runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hideHint?.let(hint::removeCallbacks)
        hideHint = Runnable { hint.visibility = View.GONE }.also { hint.postDelayed(it, 700) }
    }
}
