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
import net.minecraft.network.chat.Component

/** Screen geometry: wand selection, define (with overwrite confirm), undefine. */
internal object ScreenCommands {

    private val overwriteConfirms = ConfirmTracker()

    fun wand(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Only players can use the selection wand"))
            return 0
        }
        val on = SelectionTool.toggle(player.uuid)
        source.sendSuccess({
            Component.literal(
                if (on) {
                    "Selection mode ON: left-click one corner of the wall, right-click the opposite corner, then /pm define <name>. Run /pm wand again to cancel."
                } else {
                    "Selection mode off"
                }
            )
        }, false)
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
            source.sendFailure(Component.literal("Console must pass corners: /pm define <name> <corner1> <corner2>"))
            return 0
        }
        val selection = SelectionTool.selectionOf(player.uuid)
        val corner1 = selection?.corner1
        val corner2 = selection?.corner2
        if (selection == null || corner1 == null || corner2 == null) {
            source.sendFailure(
                Component.literal("No selection. Run /pm wand and click both corners first (or pass coordinates).")
            )
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
        if (ScreenManager.get(name) != null && !confirmOverwrite(source, name)) return 0
        if (dimension != source.level.dimension().identifier().toString()) {
            source.sendFailure(Component.literal("Selection is in another dimension; reselect the wall here"))
            return 0
        }
        val definition = try {
            ScreenDefinition.fromCorners(name, dimension, corner1, corner2, source.position.toVec3d())
        } catch (e: IllegalArgumentException) {
            source.sendFailure(Component.literal(e.message ?: "Invalid screen corners"))
            return 0
        }
        ScreenManager.define(definition)
        source.sendSuccess({
            Component.literal(
                "Defined screen '$name': ${definition.width}x${definition.height} facing ${definition.facing.serializedName}"
            )
        }, true)
        return 1
    }

    /**
     * Overwriting an existing screen takes the same command twice within 30s.
     * On the confirming run the old screen is removed here; returns whether
     * the define may proceed.
     */
    private fun confirmOverwrite(source: CommandSourceStack, name: String): Boolean {
        if (!overwriteConfirms.confirm(source.textName, name)) {
            source.sendFailure(
                Component.literal("Screen '$name' already exists. Run the same command again within 30s to overwrite it.")
            )
            return false
        }
        ScreenManager.undefine(name)
        return true
    }

    fun undefine(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        val screen = ScreenManager.get(name)
        if (screen == null) {
            source.sendFailure(Component.literal("No screen named '$name'. See /pm list"))
            return 0
        }
        ScreenManager.undefine(screen.definition.name)
        source.sendSuccess({ Component.literal("Removed screen '${screen.definition.name}'") }, true)
        return 1
    }
}
