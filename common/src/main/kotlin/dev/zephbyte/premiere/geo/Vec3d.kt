package dev.zephbyte.premiere.geo

/** Double-precision position, platform-free (see [ScreenPos]). */
data class Vec3d(val x: Double, val y: Double, val z: Double) {
    fun distanceSqrTo(other: Vec3d): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}
