package dev.zephbyte.premiere.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.screen.ManagedScreen
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.util.MediaUrls
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component

object MovieNightCommand {

    private val SCREEN_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(ScreenManager.all().map { it.definition.name }, builder)
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("movienight")
                    .then(
                        Commands.literal("define")
                            .requires(MoviePerms::canControl)
                            .then(
                                Commands.argument("screen", StringArgumentType.word())
                                    .then(
                                        Commands.argument("corner1", BlockPosArgument.blockPos())
                                            .then(
                                                Commands.argument("corner2", BlockPosArgument.blockPos())
                                                    .executes(::define)
                                            )
                                    )
                            )
                    )
                    .then(
                        Commands.literal("undefine")
                            .requires(MoviePerms::canControl)
                            .then(screenArg().executes(::undefine))
                    )
                    .then(
                        Commands.literal("play")
                            .requires(MoviePerms::canControl)
                            .then(
                                screenArg().then(
                                    Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(::play)
                                )
                            )
                    )
                    .then(
                        Commands.literal("pause")
                            .requires(MoviePerms::canControl)
                            .then(screenArg().executes(::pause))
                    )
                    .then(
                        Commands.literal("stop")
                            .requires(MoviePerms::canControl)
                            .then(screenArg().executes(::stop))
                    )
                    .then(
                        Commands.literal("volume")
                            .requires(MoviePerms::canControl)
                            .then(
                                screenArg().then(
                                    Commands.argument("volume", IntegerArgumentType.integer(0, 100))
                                        .executes(::volume)
                                )
                            )
                    )
                    .then(Commands.literal("list").executes(::list))
            )
        }
    }

    private fun screenArg() =
        Commands.argument("screen", StringArgumentType.word()).suggests(SCREEN_SUGGESTIONS)

    private fun requireScreen(context: CommandContext<CommandSourceStack>): ManagedScreen? {
        val name = StringArgumentType.getString(context, "screen")
        val screen = ScreenManager.get(name)
        if (screen == null) {
            context.source.sendFailure(Component.literal("No screen named '$name'. See /movienight list"))
        }
        return screen
    }

    private fun define(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        if (ScreenManager.get(name) != null) {
            source.sendFailure(Component.literal("Screen '$name' already exists"))
            return 0
        }
        val corner1 = BlockPosArgument.getBlockPos(context, "corner1")
        val corner2 = BlockPosArgument.getBlockPos(context, "corner2")
        val definition = try {
            ScreenDefinition.fromCorners(
                name,
                source.level.dimension().identifier().toString(),
                corner1,
                corner2,
                source.position,
            )
        } catch (e: IllegalArgumentException) {
            source.sendFailure(Component.literal(e.message ?: "Invalid screen corners"))
            return 0
        }
        ScreenManager.define(source.server, definition)
        source.sendSuccess({
            Component.literal(
                "Defined screen '$name': ${definition.width}x${definition.height} facing ${definition.facing.serializedName}"
            )
        }, true)
        return 1
    }

    private fun undefine(context: CommandContext<CommandSourceStack>): Int {
        val screen = requireScreen(context) ?: return 0
        ScreenManager.undefine(context.source.server, screen.definition.name)
        context.source.sendSuccess({ Component.literal("Removed screen '${screen.definition.name}'") }, true)
        return 1
    }

    private fun play(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val screen = requireScreen(context) ?: return 0
        val url = StringArgumentType.getString(context, "url").trim()
        MediaUrls.validate(url)?.let {
            source.sendFailure(Component.literal(it))
            return 0
        }
        val server = source.server
        // DNS resolution blocks; keep it off the server thread.
        Thread.startVirtualThread {
            val error = MediaUrls.validateResolved(url)
            server.execute {
                if (error != null) {
                    source.sendFailure(Component.literal(error))
                    return@execute
                }
                ScreenManager.play(server, screen, url)
                source.sendSuccess({ Component.literal("Playing on '${screen.definition.name}': $url") }, true)
            }
        }
        return 1
    }

    private fun pause(context: CommandContext<CommandSourceStack>): Int {
        val screen = requireScreen(context) ?: return 0
        if (screen.playback.state == PlayState.STOPPED) {
            context.source.sendFailure(Component.literal("Nothing is playing on '${screen.definition.name}'"))
            return 0
        }
        val playing = ScreenManager.togglePause(context.source.server, screen)
        val verb = if (playing) "Resumed" else "Paused"
        context.source.sendSuccess({ Component.literal("$verb '${screen.definition.name}'") }, true)
        return 1
    }

    private fun stop(context: CommandContext<CommandSourceStack>): Int {
        val screen = requireScreen(context) ?: return 0
        ScreenManager.stop(context.source.server, screen)
        context.source.sendSuccess({ Component.literal("Stopped '${screen.definition.name}'") }, true)
        return 1
    }

    private fun volume(context: CommandContext<CommandSourceStack>): Int {
        val screen = requireScreen(context) ?: return 0
        val volume = IntegerArgumentType.getInteger(context, "volume")
        ScreenManager.setVolume(context.source.server, screen, volume / 100f)
        context.source.sendSuccess({ Component.literal("Volume on '${screen.definition.name}' set to $volume%") }, true)
        return 1
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val screens = ScreenManager.all()
        if (screens.isEmpty()) {
            context.source.sendSuccess({ Component.literal("No screens defined") }, false)
            return 0
        }
        for (screen in screens) {
            val d = screen.definition
            val p = screen.playback
            val status = when (p.state) {
                PlayState.STOPPED -> "idle"
                PlayState.PAUSED -> "paused at ${p.currentPositionMs() / 1000}s: ${p.url}"
                PlayState.PLAYING -> "playing at ${p.currentPositionMs() / 1000}s: ${p.url}"
            }
            context.source.sendSuccess({
                Component.literal(
                    "${d.name}: ${d.width}x${d.height} at (${d.origin.x}, ${d.origin.y}, ${d.origin.z}) facing ${d.facing.serializedName}, $status"
                )
            }, false)
        }
        return screens.size
    }
}
