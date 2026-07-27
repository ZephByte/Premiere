package dev.zephbyte.premiere.screen

enum class PlayState { PLAYING, PAUSED, STOPPED, LOADED }

/**
 * The master clock. The server owns this record; audio and every video client
 * derive their position from it, so there is exactly one authoritative timeline.
 */
class Playback {
    var url: String = ""

    /** What staff typed (library name or pasted URL); for display only. */
    var label: String = ""
    var state: PlayState = PlayState.STOPPED
    var volume: Float = 1f

    /**
     * Bumped on every fresh play/load (not pause/resume/volume) so the audio
     * side can tell "resume this session" apart from "start over at 0".
     */
    var generation: Int = 0
        private set

    /** Media position at the wall-clock instant [anchorMs]. */
    private var positionMs: Long = 0
    private var anchorMs: Long = 0

    fun currentPositionMs(now: Long = System.currentTimeMillis()): Long =
        if (state == PlayState.PLAYING) positionMs + (now - anchorMs) else positionMs

    /** Presigned sidecar subtitles, or "" when the film has none. */
    var subtitleUrl: String = ""

    /** Per-film audio track language override; "" defers to the server config. */
    var audioLanguage: String = ""

    fun play(
        url: String,
        label: String = url,
        subtitleUrl: String = "",
        audioLanguage: String = "",
        now: Long = System.currentTimeMillis(),
    ) {
        this.url = url
        this.label = label
        this.subtitleUrl = subtitleUrl
        this.audioLanguage = audioLanguage
        positionMs = 0
        anchorMs = now
        state = PlayState.PLAYING
        generation++
    }

    /** Primed at position 0: decoders open and buffer, the clock doesn't run. */
    fun load(
        url: String,
        label: String = url,
        subtitleUrl: String = "",
        audioLanguage: String = "",
        now: Long = System.currentTimeMillis(),
    ) {
        this.url = url
        this.label = label
        this.subtitleUrl = subtitleUrl
        this.audioLanguage = audioLanguage
        positionMs = 0
        anchorMs = now
        state = PlayState.LOADED
        generation++
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
        label = ""
        subtitleUrl = ""
        audioLanguage = ""
    }
}
