package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.platform.PlayerHandle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WorldEdit-style corner selection without a wand *item* — registering an
 * item is the one thing this mod must never do (it would trip the registry
 * sync and kick vanilla clients). Instead, /pm wand toggles a
 * per-player mode in which left-click marks corner 1 and right-click corner
 * 2; the clicks are swallowed so nothing breaks or places. /movienight
 * define <name> then reads the selection.
 *
 * Platform-free core: each loader forwards its block-click events into
 * [handleClick] and swallows the click when it returns true.
 */
object SelectionTool {

    class Selection {
        var corner1: ScreenPos? = null
        var corner2: ScreenPos? = null
        var dimension: String? = null
    }

    private val selecting = ConcurrentHashMap<UUID, Selection>()

    /** Returns true if selection mode is now on. */
    fun toggle(playerUuid: UUID): Boolean {
        val was = selecting.remove(playerUuid) != null
        if (!was) selecting[playerUuid] = Selection()
        return !was
    }

    fun selectionOf(playerUuid: UUID): Selection? = selecting[playerUuid]

    fun clear(playerUuid: UUID) {
        selecting.remove(playerUuid)
    }

    /**
     * A block click from a player in selection mode. Returns true when the
     * click was consumed (the platform must cancel it so nothing breaks or
     * places), false to let it through untouched.
     */
    fun handleClick(player: PlayerHandle, dimension: String, pos: ScreenPos, first: Boolean): Boolean {
        val selection = selecting[player.uuid] ?: return false
        if (!player.hasControlPermission()) {
            selecting.remove(player.uuid)
            return false
        }

        selection.dimension = dimension
        val which: String
        if (first) {
            selection.corner1 = pos
            which = "Corner 1"
        } else {
            selection.corner2 = pos
            which = "Corner 2"
        }
        // Action bar rather than chat: less spam while clicking around.
        player.sendActionBar("$which set: ${pos.x} ${pos.y} ${pos.z}" + readiness(selection))
        return true
    }

    private fun readiness(selection: Selection): String =
        if (selection.corner1 != null && selection.corner2 != null) {
            " — run /pm define <name>"
        } else {
            ""
        }
}
