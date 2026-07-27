package dev.zephbyte.premiere.geo

/**
 * Block position, platform-free. :common must not touch net.minecraft.*
 * (Fabric runs intermediary mappings, Paper runs mojmap — a shared jar can
 * only reference classes that exist identically on both), so the few MC
 * geometry types Premiere needs live here; loaders convert at their edges.
 */
data class ScreenPos(val x: Int, val y: Int, val z: Int)
