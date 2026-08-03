package dev.zephbyte.premiere.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.screen.ScreenDefinition
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.screen.SelectionTool
import dev.zephbyte.premiere.toScreenPos
import dev.zephbyte.premiere.toVec3d
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

/** Screen geometry: wand selection, define (with overwrite confirm), undefine. */
internal object ScreenCommands {

    private val overwriteConfirms = ConfirmTracker()

    fun wand(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            CommandFeedback.sendError(source, "The selection wand can only be used in-game.")
            return 0
        }
        val on = SelectionTool.toggle(player.uuid)
        CommandFeedback.sendInfo(
            source,
            if (on) {
                "Selection mode enabled. Left-click one wall corner, right-click the opposite corner, then run /pm define <name>."
            } else {
                "Selection mode disabled."
            },
        )
        return 1
    }

    fun defineFromCorners(context: CommandContext<CommandSourceStack>): Int {
        val corner1 = BlockPosArgument.getBlockPos(context, "corner1").toScreenPos()
        val corner2 = BlockPosArgument.getBlockPos(context, "corner2").toScreenPos()
        return define(context, corner1, corner2, context.source.level.dimension().identifier().toString())
    }

    fun defineFromSelection(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            CommandFeedback.sendError(source, "Console usage: /pm define <name> <corner1> <corner2>")
            return 0
        }
        val selection = SelectionTool.selectionOf(player.uuid)
        val corner1 = selection?.corner1
        val corner2 = selection?.corner2
        if (selection == null || corner1 == null || corner2 == null) {
            CommandFeedback.sendError(source, "No complete selection. Run /pm wand and click both wall corners first.")
            return 0
        }
        val result = define(context, corner1, corner2, selection.dimension ?: "")
        if (result == 1) SelectionTool.clear(player.uuid)
        return result
    }

    private fun define(
        context: CommandContext<CommandSourceStack>,
        corner1: ScreenPos,
        corner2: ScreenPos,
        dimension: String,
    ): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        if (dimension != source.level.dimension().identifier().toString()) {
            CommandFeedback.sendError(source, "That selection is in another dimension. Select the wall again here.")
            return 0
        }
        val definition = try {
            ScreenDefinition.fromCorners(name, dimension, corner1, corner2, source.position.toVec3d())
        } catch (e: IllegalArgumentException) {
            CommandFeedback.sendError(source, e.message ?: "Those screen corners are invalid.")
            return 0
        }
        val replacing = ScreenManager.get(name) != null
        if (replacing && !confirmOverwrite(source, name)) return 0
        val saved = if (replacing) ScreenManager.redefine(definition) else ScreenManager.define(definition)
        if (!saved) {
            CommandFeedback.sendError(source, "Couldn't save $name. The previous screen is unchanged; check the server log.")
            return 0
        }
        CommandFeedback.sendSuccess(
            source,
            "Defined $name — ${definition.width}×${definition.height}, facing ${definition.facing.serializedName}.",
        )
        return 1
    }

    /**
     * Overwriting an existing screen takes the same command twice within 30s.
     * Validation happens before this confirmation. The manager then swaps the
     * definition in one step, so an invalid replacement cannot erase the old
     * screen.
     */
    private fun confirmOverwrite(source: CommandSourceStack, name: String): Boolean {
        if (!overwriteConfirms.confirm(source.textName, name)) {
            CommandFeedback.sendError(source, "$name already exists. Repeat the command within 30 seconds to replace it.")
            return false
        }
        return true
    }

    fun undefine(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        val screen = ScreenManager.get(name)
        if (screen == null) {
            CommandFeedback.sendError(source, "No screen is named $name. Run /pm list to see defined screens.")
            return 0
        }
        if (!ScreenManager.undefine(screen.definition.name)) {
            CommandFeedback.sendError(source, "Couldn't remove $name. It is unchanged; check the server log.")
            return 0
        }
        CommandFeedback.sendSuccess(source, "Removed screen ${screen.definition.name}.")
        return 1
    }
}
