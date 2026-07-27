package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.geo.Vec3d

/**
 * Optional-leading-screen parsing shared by every playback command on both
 * platforms: if the first word names a screen, it's explicit; otherwise the
 * whole input applies to the implicit target — the only screen, or the
 * nearest one in the invoker's dimension.
 */
object ScreenTargeting {

    sealed interface Result {
        data class Target(val screen: ManagedScreen, val rest: String) : Result
        data class Fail(val message: String) : Result
    }

    /**
     * [dimension]/[position] are the invoker's, or null for console (which
     * has no location to be "nearest" to).
     */
    fun resolve(rawArgs: String, dimension: String?, position: Vec3d?): Result {
        val trimmed = rawArgs.trim()
        if (trimmed.isNotEmpty()) {
            val first = trimmed.substringBefore(' ')
            ScreenManager.get(first)?.let {
                return Result.Target(it, trimmed.substringAfter(' ', "").trim())
            }
        }
        ScreenManager.single()?.let { return Result.Target(it, trimmed) }
        if (ScreenManager.all().isEmpty()) {
            return Result.Fail("No screens defined yet. /pm wand, then /pm define <name>")
        }
        if (dimension == null || position == null) {
            return Result.Fail("Multiple screens exist; name one: /pm <command> <screen> ...")
        }
        val nearest = ScreenManager.nearestTo(dimension, position)
            ?: return Result.Fail("No screens in this dimension. /pm list")
        return Result.Target(nearest, trimmed)
    }
}
