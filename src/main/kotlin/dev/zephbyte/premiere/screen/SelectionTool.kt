package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.command.MoviePerms
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WorldEdit-style corner selection without a wand *item* — registering an
 * item is the one thing this mod must never do (it would trip the registry
 * sync and kick vanilla clients). Instead, /pm wand toggles a
 * per-player mode in which left-click marks corner 1 and right-click corner
 * 2; the clicks are swallowed so nothing breaks or places. /movienight
 * define <name> then reads the selection.
 */
object SelectionTool {

    class Selection {
        var corner1: BlockPos? = null
        var corner2: BlockPos? = null
        var dimension: String? = null
    }

    private val selecting = ConcurrentHashMap<UUID, Selection>()

    /** Returns true if selection mode is now on. */
    fun toggle(player: ServerPlayer): Boolean {
        val was = selecting.remove(player.uuid) != null
        if (!was) selecting[player.uuid] = Selection()
        return !was
    }

    fun selectionOf(player: ServerPlayer): Selection? = selecting[player.uuid]

    fun clear(player: ServerPlayer) {
        selecting.remove(player.uuid)
    }

    fun register() {
        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            handleClick(player, level, pos, first = true)
        }
        UseBlockCallback.EVENT.register { player, level, _, hit ->
            handleClick(player, level, hit.blockPos, first = false)
        }
    }

    private fun handleClick(
        player: net.minecraft.world.entity.player.Player,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        first: Boolean,
    ): InteractionResult {
        if (level.isClientSide) {
            // Selection state is server-only; a modded client may briefly
            // predict a break before the server's cancel reverts it. Harmless.
            return InteractionResult.PASS
        }
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val selection = selecting[serverPlayer.uuid] ?: return InteractionResult.PASS
        if (!MoviePerms.canControl(serverPlayer.createCommandSourceStack())) {
            selecting.remove(serverPlayer.uuid)
            return InteractionResult.PASS
        }

        selection.dimension = level.dimension().identifier().toString()
        val which: String
        if (first) {
            selection.corner1 = pos
            which = "Corner 1"
        } else {
            selection.corner2 = pos
            which = "Corner 2"
        }
        // Action bar rather than chat: less spam while clicking around.
        serverPlayer.sendOverlayMessage(
            Component.literal("$which set: ${pos.x} ${pos.y} ${pos.z}" + readiness(selection))
        )
        return InteractionResult.FAIL // swallow the click; nothing breaks or places
    }

    private fun readiness(selection: Selection): String =
        if (selection.corner1 != null && selection.corner2 != null) {
            " — run /pm define <name>"
        } else {
            ""
        }
}
