package com.example.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.TrafficStats
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Live "KB/S" badge for an ExoPlayer based streaming screen.
 *
 * The bug this replaces: the label was written once from the first buffer
 * callback, so it froze at the first non-zero value (e.g. 127 KB/S).
 *
 * Usage:
 *   val overlay = SpeedOverlay(context, player, speedTextView)
 *   overlay.start()   // onStart / when playback begins
 *   overlay.stop()    // onStop / onDestroy - always call this
 */
class SpeedOverlay(
    private val player: ExoPlayer,
    private val label: TextView,
) {
    private val bandwidthMeter: DefaultBandwidthMeter =
        DefaultBandwidthMeter.Builder(label.context).build()

    private val main = Handler(Looper.getMainLooper())
    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTickAt = SystemClock.elapsedRealtime()

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val seconds = (now - lastTickAt).coerceAtLeast(1L) / 1000.0
            lastTickAt = now

            val kbPerSec = when {
                player.isPlaying -> bandwidthKbPerSec().takeIf { it > 0 } ?: systemKbPerSec(seconds)
                else -> 0
            }

            label.text = "$kbPerSec KB/S"
            main.postDelayed(this, 1_000L)
        }
    }

    /** Bits/second estimate of the player's own loader. -1 when unknown. */
    private fun bandwidthKbPerSec(): Int {
        val bits = bandwidthMeter.bitrateEstimate
        return if (bits > 0) (bits / 8 / 1024).toInt() else -1
    }

    /** Fallback: delta of total device RX bytes. Includes other apps, so approximate. */
    private fun systemKbPerSec(seconds: Double): Int {
        val rx = TrafficStats.getTotalRxBytes()
        if (rx <= 0L) return 0
        val delta = (rx - lastRxBytes).coerceAtLeast(0L)
        lastRxBytes = rx
        return (delta / 1024.0 / seconds).toInt()
    }

    fun start() {
        main.removeCallbacks(tick)
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTickAt = SystemClock.elapsedRealtime()
        main.post(tick)
    }

    fun stop() {
        main.removeCallbacks(tick)
        label.text = "0 KB/S"
    }
}
