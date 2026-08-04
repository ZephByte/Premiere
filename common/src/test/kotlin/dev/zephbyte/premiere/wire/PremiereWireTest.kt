package dev.zephbyte.premiere.wire

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals

class PremiereWireTest {

    @Test
    fun `screen state round trips with its timeline generation`() {
        val message = ScreenStateMessage(
            screen = ScreenDefinition(
                "theater",
                "minecraft:overworld",
                ScreenPos(10, 64, -5),
                16,
                9,
                ScreenFacing.SOUTH,
            ),
            url = "https://example.com/movie.mp4",
            subtitleUrl = "https://example.com/movie.srt",
            audioLanguage = "eng",
            audioDistance = 48f,
            audioFullVolumeRadius = 16f,
            state = PlayState.PLAYING,
            generation = 42,
            mediaPositionMs = 123_456L,
            volume = 0.75f,
            removed = false,
        )
        val buffer = Unpooled.buffer()

        PremiereWire.writeScreenState(buffer, message)

        assertEquals(message, PremiereWire.readScreenState(buffer))
        assertEquals(0, buffer.readableBytes())
    }

    @Test
    fun `ready message identifies the exact load generation`() {
        val message = ScreenReadyMessage("theater", 7, 7_654_321L)
        val buffer = Unpooled.buffer()

        PremiereWire.writeScreenReady(buffer, message)

        assertEquals(message, PremiereWire.readScreenReady(buffer))
    }
}
