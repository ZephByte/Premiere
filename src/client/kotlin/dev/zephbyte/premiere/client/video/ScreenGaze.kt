package dev.zephbyte.premiere.client.video

import dev.zephbyte.premiere.client.ClientScreens
import dev.zephbyte.premiere.screen.PlayState
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction

/**
 * "Is the player looking at a screen with a film up?" — a ray/rectangle test
 * against each active wall, used to melt the crosshair out of the picture
 * without touching the rest of the HUD (F1 hides subtitles and chat too,
 * which is the opposite of what someone watching a film wants).
 */
object ScreenGaze {

    private const val MAX_DISTANCE = 64.0

    fun lookingAtActiveScreen(): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        val level = minecraft.level ?: return false
        val dimension = level.dimension().identifier().toString()
        val eye = player.getEyePosition()
        val direction = player.getViewVector(1.0f)

        for (active in ClientScreens.renderable()) {
            if (active.state != PlayState.PLAYING && active.state != PlayState.PAUSED) continue
            if (active.player == null) continue
            val definition = active.definition
            if (definition.dimension != dimension) continue

            // Ray vs the wall's plane (the wall is one block thick; its center
            // plane with a half-block margin is plenty for a crosshair test).
            val origin = definition.origin
            val alongX = definition.facing.axis == Direction.Axis.Z // wall spans X
            val planeCoord = (if (alongX) origin.z else origin.x) + 0.5
            val eyeCoord = if (alongX) eye.z else eye.x
            val directionCoord = if (alongX) direction.z else direction.x
            if (directionCoord == 0.0) continue
            val t = (planeCoord - eyeCoord) / directionCoord
            if (t < 0 || t > MAX_DISTANCE) continue

            val hit = eye.add(direction.scale(t))
            val span = if (alongX) hit.x - origin.x else hit.z - origin.z
            val heightOnWall = hit.y - origin.y
            if (span in -0.25..definition.width + 0.25 && heightOnWall in -0.25..definition.height + 0.25) {
                return true
            }
        }
        return false
    }
}
