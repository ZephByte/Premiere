package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import kotlin.math.abs

/**
 * A movie screen is pure server-side data over an ordinary block wall: no custom
 * block, no display entity. Mod-less players just see the wall.
 *
 * The wall is a one-block-thick vertical rectangle. [origin] is the minimum
 * corner; the wall spans [width] blocks along X or Z (whichever axis [facing]
 * is not on) and [height] blocks up along Y. [facing] points out of the screen
 * toward the audience.
 */
data class ScreenDefinition(
    val name: String,
    val dimension: String,
    val origin: ScreenPos,
    val width: Int,
    val height: Int,
    val facing: ScreenFacing,
) {
    /** Center of the visible face, used as the audio source position. */
    fun faceCenter(): Vec3d {
        val (x, z) = when (facing) {
            ScreenFacing.SOUTH -> (origin.x + width / 2.0) to (origin.z + 1.0)
            ScreenFacing.NORTH -> (origin.x + width / 2.0) to origin.z.toDouble()
            ScreenFacing.EAST -> (origin.x + 1.0) to (origin.z + width / 2.0)
            ScreenFacing.WEST -> origin.x.toDouble() to (origin.z + width / 2.0)
        }
        return Vec3d(x, origin.y + height / 2.0, z)
    }

    companion object {
        const val MAX_EDGE = 64

        /**
         * Builds a screen from two opposite corners of a flat vertical wall.
         * The side the definer is standing on becomes the front.
         * Throws [IllegalArgumentException] with a player-readable message.
         */
        fun fromCorners(name: String, dimension: String, a: ScreenPos, b: ScreenPos, viewer: Vec3d): ScreenDefinition {
            require(a.y != b.y) { "Corners must differ in height (the wall is vertical)" }
            val flatX = a.x == b.x
            val flatZ = a.z == b.z
            require(flatX != flatZ) { "Corners must be on a flat wall: exactly one of X or Z must match" }

            val origin = ScreenPos(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
            val width = if (flatX) abs(a.z - b.z) + 1 else abs(a.x - b.x) + 1
            val height = abs(a.y - b.y) + 1
            require(width in 2..MAX_EDGE && height in 2..MAX_EDGE) {
                "Screen must be between 2x2 and ${MAX_EDGE}x$MAX_EDGE blocks (got ${width}x$height)"
            }

            val facing = if (flatX) {
                if (viewer.x >= origin.x + 0.5) ScreenFacing.EAST else ScreenFacing.WEST
            } else {
                if (viewer.z >= origin.z + 0.5) ScreenFacing.SOUTH else ScreenFacing.NORTH
            }
            return ScreenDefinition(name, dimension, origin, width, height, facing)
        }
    }
}
