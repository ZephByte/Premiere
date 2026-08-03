package dev.zephbyte.premiere.client.video

import dev.zephbyte.premiere.client.ClientScreens
import dev.zephbyte.premiere.toVec3
import dev.zephbyte.premiere.toVec3d
import dev.zephbyte.premiere.screen.PlayState
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult

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

            val target = definition.frontRayIntersection(
                eye.toVec3d(),
                direction.toVec3d(),
                MAX_DISTANCE,
            )?.toVec3() ?: continue

            // The screen wall itself may be the raycast result at the target
            // distance. Only a collision meaningfully closer than the visible
            // face is an obstruction.
            val blockHit = level.clip(
                ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            )
            if (blockHit.type != HitResult.Type.MISS &&
                blockHit.location.distanceToSqr(target) > OCCLUSION_EPSILON_SQ
            ) continue

            return true
        }
        return false
    }

    private const val OCCLUSION_EPSILON_SQ = 1.0e-4
}
