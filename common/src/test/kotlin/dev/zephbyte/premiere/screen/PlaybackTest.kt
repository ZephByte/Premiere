package dev.zephbyte.premiere.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTest {

    @Test
    fun `fresh play and seek advance the timeline generation`() {
        val playback = Playback()

        playback.play("https://example.com/movie.mp4", now = 1_000L)
        assertEquals(1, playback.generation)
        assertEquals(PlayState.PLAYING, playback.state)
        assertEquals(2_500L, playback.currentPositionMs(3_500L))

        playback.seek(750L, now = 4_000L)
        assertEquals(2, playback.generation)
        assertEquals(1_250L, playback.currentPositionMs(4_500L))

        // Replaying the same URL is still a new timeline.
        playback.play("https://example.com/movie.mp4", now = 5_000L)
        assertEquals(3, playback.generation)
        assertEquals(0L, playback.currentPositionMs(5_000L))
    }

    @Test
    fun `pause freezes and resume preserves the anchored position`() {
        val playback = Playback()
        playback.play("https://example.com/movie.mp4", now = 1_000L)

        playback.pause(now = 2_250L)
        assertEquals(1_250L, playback.currentPositionMs(9_000L))

        playback.resume(now = 10_000L)
        assertEquals(1_750L, playback.currentPositionMs(10_500L))
    }
}
