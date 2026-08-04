package dev.zephbyte.premiere.audio

import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.screen.ScreenDefinition
import kotlin.math.sqrt

/** Audience-shaped gain for one screen, independent of the audio backend. */
object ScreenAudioZone {

    /**
     * The front half of [fullVolumeRadius] is a full-volume sweet spot.
     * Beyond it, gain falls linearly to silence at [maxDistance]. Behind the
     * screen there is no sweet spot, so attenuation begins immediately.
     */
    fun gain(
        screen: ScreenDefinition,
        listener: Vec3d,
        fullVolumeRadius: Float,
        maxDistance: Float,
    ): Float {
        if (maxDistance <= 0f) return 0f
        val center = screen.faceCenter()
        val dx = listener.x - center.x
        val dy = listener.y - center.y
        val dz = listener.z - center.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val inFront = dx * screen.facing.stepX + dz * screen.facing.stepZ >= 0.0

        if (inFront) {
            val radius = fullVolumeRadius.coerceIn(0f, maxDistance).toDouble()
            if (distance <= radius) return 1f
            val falloffRange = maxDistance - radius
            if (falloffRange <= 0.0) return 0f
            return (1.0 - (distance - radius) / falloffRange).coerceIn(0.0, 1.0).toFloat()
        }

        return (1.0 - distance / maxDistance).coerceIn(0.0, 1.0).toFloat()
    }
}
