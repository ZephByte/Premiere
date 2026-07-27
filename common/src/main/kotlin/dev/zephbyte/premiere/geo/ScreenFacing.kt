package dev.zephbyte.premiere.geo

/**
 * Horizontal facing of a screen's front. Serialized names match vanilla
 * Direction.serializedName so premiere_screens.json written by older builds
 * round-trips unchanged.
 */
enum class ScreenFacing(val stepX: Int, val stepZ: Int) {
    NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

    val serializedName: String get() = name.lowercase()

    /** True when the wall spans the X axis (i.e. the screen faces north/south). */
    val spansX: Boolean get() = stepZ != 0

    companion object {
        fun byName(name: String?): ScreenFacing? =
            entries.firstOrNull { it.serializedName == name }
    }
}
