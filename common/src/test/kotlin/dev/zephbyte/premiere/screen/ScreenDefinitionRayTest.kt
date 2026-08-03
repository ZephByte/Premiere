package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenDefinitionRayTest {

    @Test
    fun `front ray reaches the visible face for every facing`() {
        ScreenFacing.entries.forEach { facing ->
            val screen = screen(facing)
            val center = screen.faceCenter()
            val eye = Vec3d(center.x + facing.stepX * 5.0, center.y, center.z + facing.stepZ * 5.0)
            val direction = Vec3d(-facing.stepX.toDouble(), 0.0, -facing.stepZ.toDouble())

            assertEquals(center, screen.frontRayIntersection(eye, direction, 64.0), facing.name)
        }
    }

    @Test
    fun `viewer behind the screen cannot target its face`() {
        val screen = screen(ScreenFacing.SOUTH)
        assertNull(
            screen.frontRayIntersection(
                eye = Vec3d(2.0, 65.5, -4.0),
                direction = Vec3d(0.0, 0.0, 1.0),
                maxDistance = 64.0,
            )
        )
    }

    @Test
    fun `ray must land inside the screen and within range`() {
        val screen = screen(ScreenFacing.SOUTH)
        assertNull(screen.frontRayIntersection(Vec3d(5.0, 65.5, 5.0), Vec3d(0.0, 0.0, -1.0), 64.0))
        assertNull(screen.frontRayIntersection(Vec3d(2.0, 65.5, 5.0), Vec3d(0.0, 0.0, -1.0), 3.0))
        assertNull(screen.frontRayIntersection(Vec3d(2.0, 65.5, 5.0), Vec3d(0.0, 0.0, 1.0), 64.0))
    }

    private fun screen(facing: ScreenFacing) = ScreenDefinition(
        name = "theater",
        dimension = "minecraft:overworld",
        origin = ScreenPos(0, 64, 0),
        width = 4,
        height = 3,
        facing = facing,
    )
}
