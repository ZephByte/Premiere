package dev.zephbyte.premiere.audio

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.screen.ScreenDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenAudioZoneTest {

    private val screen = ScreenDefinition(
        "theater",
        "minecraft:overworld",
        ScreenPos(0, 64, 0),
        8,
        6,
        ScreenFacing.SOUTH,
    )
    private val center = screen.faceCenter()

    @Test
    fun `front audience zone stays at full volume`() {
        assertEquals(1f, gain(front(2.0)))
        assertEquals(1f, gain(front(16.0)))
    }

    @Test
    fun `front audio falls from the audience radius to silence`() {
        assertEquals(0.5f, gain(front(32.0)), 0.0001f)
        assertEquals(0f, gain(front(48.0)))
        assertEquals(0f, gain(front(60.0)))
    }

    @Test
    fun `behind the screen has no full volume audience zone`() {
        val justBehind = gain(front(-2.0))
        assertTrue(justBehind in 0f..<1f)
        assertEquals(0f, gain(front(-48.0)))
    }

    @Test
    fun `height contributes to distance from the screen source`() {
        assertTrue(gain(Vec3d(center.x, center.y + 20.0, center.z + 2.0)) < 1f)
    }

    @Test
    fun `audience side follows every screen facing`() {
        ScreenFacing.entries.forEach { facing ->
            val facingScreen = screen.copy(facing = facing)
            val facingCenter = facingScreen.faceCenter()
            val front = Vec3d(
                facingCenter.x + facing.stepX * 8.0,
                facingCenter.y,
                facingCenter.z + facing.stepZ * 8.0,
            )
            val back = Vec3d(
                facingCenter.x - facing.stepX * 8.0,
                facingCenter.y,
                facingCenter.z - facing.stepZ * 8.0,
            )

            assertEquals(1f, ScreenAudioZone.gain(facingScreen, front, 16f, 48f), facing.name)
            assertTrue(ScreenAudioZone.gain(facingScreen, back, 16f, 48f) < 1f, facing.name)
        }
    }

    private fun front(distance: Double) =
        Vec3d(center.x, center.y, center.z + distance)

    private fun gain(listener: Vec3d): Float =
        ScreenAudioZone.gain(screen, listener, fullVolumeRadius = 16f, maxDistance = 48f)
}
