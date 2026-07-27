package dev.zephbyte.premiere.screen

enum class PlayState { PLAYING, PAUSED, STOPPED }

/**
 * The master clock. The server owns this record; audio and every video client
 * derive their position from it, so there is exactly one authoritative timeline.
 */
class Playback {
    var url: String = ""
    var state: PlayState = PlayState.STOPPED
    var volume: Float = 1f

    /** Media position at the wall-clock instant [anchorMs]. */
    private var positionMs: Long = 0
    private var anchorMs: Long = 0

    fun currentPositionMs(now: Long = System.currentTimeMillis()): Long =
        if (state == PlayState.PLAYING) positionMs + (now - anchorMs) else positionMs

    fun play(url: String, now: Long = System.currentTimeMillis()) {
        this.url = url
        positionMs = 0
        anchorMs = now
        state = PlayState.PLAYING
    }

    fun pause(now: Long = System.currentTimeMillis()) {
        positionMs = currentPositionMs(now)
        anchorMs = now
        state = PlayState.PAUSED
    }

    fun resume(now: Long = System.currentTimeMillis()) {
        anchorMs = now
        state = PlayState.PLAYING
    }

    fun stop() {
        state = PlayState.STOPPED
        positionMs = 0
        url = ""
    }
}
