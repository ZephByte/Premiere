package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.FabricPlayerHandle
import dev.zephbyte.premiere.toScreenPos
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/** Fabric event glue for the platform-free [SelectionTool]. */
object SelectionToolEvents {

    fun register() {
        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            handleClick(player, level, pos, first = true)
        }
        UseBlockCallback.EVENT.register { player, level, _, hit ->
            handleClick(player, level, hit.blockPos, first = false)
        }
    }

    private fun handleClick(player: Player, level: Level, pos: BlockPos, first: Boolean): InteractionResult {
        if (level.isClientSide) {
            // Selection state is server-only; a modded client may briefly
            // predict a break before the server's cancel reverts it. Harmless.
            return InteractionResult.PASS
        }
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val consumed = SelectionTool.handleClick(
            FabricPlayerHandle(serverPlayer),
            level.dimension().identifier().toString(),
            pos.toScreenPos(),
            first,
        )
        // FAIL swallows the click so nothing breaks or places.
        return if (consumed) InteractionResult.FAIL else InteractionResult.PASS
    }
}
