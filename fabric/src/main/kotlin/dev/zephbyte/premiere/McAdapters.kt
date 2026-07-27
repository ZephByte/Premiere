package dev.zephbyte.premiere

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Conversions between :common's platform-free geometry and Minecraft's types.
 * These live only at the Fabric edge; :common never sees net.minecraft.*.
 */

fun BlockPos.toScreenPos(): ScreenPos = ScreenPos(x, y, z)
fun ScreenPos.toBlockPos(): BlockPos = BlockPos(x, y, z)

fun Vec3.toVec3d(): Vec3d = Vec3d(x, y, z)
fun Vec3d.toVec3(): Vec3 = Vec3(x, y, z)

fun Direction.toScreenFacing(): ScreenFacing = when (this) {
    Direction.NORTH -> ScreenFacing.NORTH
    Direction.SOUTH -> ScreenFacing.SOUTH
    Direction.EAST -> ScreenFacing.EAST
    Direction.WEST -> ScreenFacing.WEST
    else -> throw IllegalArgumentException("Screens only face horizontally, got $this")
}

fun ScreenFacing.toDirection(): Direction = when (this) {
    ScreenFacing.NORTH -> Direction.NORTH
    ScreenFacing.SOUTH -> Direction.SOUTH
    ScreenFacing.EAST -> Direction.EAST
    ScreenFacing.WEST -> Direction.WEST
}
