package dev.zephbyte.premiere.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.zephbyte.premiere.screen.ManagedScreen
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.upload.MovieLibrary
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component

/**
 * The command tree and shared target resolution. Handlers live beside it:
 * [ScreenCommands] (wand/define/undefine), [PlaybackCommands]
 * (play/load/pause/stop/seek/volume), [AdminCommands] (upload/movies/
 * reload/list).
 */
object PremiereCommand {

    internal val SCREEN_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(ScreenManager.all().map { it.definition.name }, builder)
    }

    /** For play/load, the first word can be a screen or a movie. */
    private val SCREEN_OR_MOVIE_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(
            ScreenManager.all().map { it.definition.name } + MovieLibrary.suggestions(),
            builder,
        )
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            // /premiere is canonical (it's the mod's name), /pm is the short
            // everyday form, /movienight survives for muscle memory.
            for (alias in listOf("premiere", "pm", "movienight")) {
                dispatcher.register(buildTree(alias))
            }
        }
    }

    private fun buildTree(root: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(root)
            .then(
                Commands.literal("wand")
                    .requires(MoviePerms::canControl)
                    .executes(ScreenCommands::wand)
            )
            .then(
                Commands.literal("define")
                    .requires(MoviePerms::canControl)
                    .then(
                        Commands.argument("screen", StringArgumentType.word())
                            .executes(ScreenCommands::defineFromSelection)
                            .then(
                                Commands.argument("corner1", BlockPosArgument.blockPos())
                                    .then(
                                        Commands.argument("corner2", BlockPosArgument.blockPos())
                                            .executes(ScreenCommands::defineFromCorners)
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("undefine")
                    .requires(MoviePerms::canControl)
                    .then(screenArg().executes(ScreenCommands::undefine))
            )
            // Playback commands take an optional leading screen name; without
            // one they target the nearest screen (or the only one). So
            // `/pm play bunny` just works in the theater.
            .then(
                Commands.literal("play")
                    .requires(MoviePerms::canControl)
                    .executes { PlaybackCommands.play(it, "") }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(SCREEN_OR_MOVIE_SUGGESTIONS)
                            .executes { PlaybackCommands.play(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("load")
                    .requires(MoviePerms::canControl)
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(SCREEN_OR_MOVIE_SUGGESTIONS)
                            .executes { PlaybackCommands.load(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("pause")
                    .requires(MoviePerms::canControl)
                    .executes { PlaybackCommands.pause(it, "") }
                    .then(
                        screenArg().executes { PlaybackCommands.pause(it, StringArgumentType.getString(it, "screen")) }
                    )
            )
            .then(
                Commands.literal("stop")
                    .requires(MoviePerms::canControl)
                    .executes { PlaybackCommands.stop(it, "") }
                    .then(
                        screenArg().executes { PlaybackCommands.stop(it, StringArgumentType.getString(it, "screen")) }
                    )
            )
            .then(
                Commands.literal("seek")
                    .requires(MoviePerms::canControl)
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(SCREEN_SUGGESTIONS)
                            .executes { PlaybackCommands.seek(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("volume")
                    .requires(MoviePerms::canControl)
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(SCREEN_SUGGESTIONS)
                            .executes { PlaybackCommands.volume(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("upload")
                    .requires(MoviePerms::canControl)
                    .executes(AdminCommands::upload)
            )
            .then(
                Commands.literal("movies")
                    .requires(MoviePerms::canControl)
                    .executes(AdminCommands::movies)
            )
            .then(
                Commands.literal("reload")
                    .requires(MoviePerms::canControl)
                    .executes(AdminCommands::reload)
            )
            .then(Commands.literal("list").executes(AdminCommands::list))

    private fun screenArg() =
        Commands.argument("screen", StringArgumentType.word()).suggests(SCREEN_SUGGESTIONS)

    /**
     * Optional-leading-screen parsing: if the first word names a screen, it's
     * explicit; otherwise the whole input applies to the implicit target —
     * the only screen, or the nearest one in the player's dimension.
     * Failure messages have already been sent when this returns null.
     */
    internal fun targetAndRest(source: CommandSourceStack, rawArgs: String): Pair<ManagedScreen, String>? {
        val trimmed = rawArgs.trim()
        if (trimmed.isNotEmpty()) {
            val first = trimmed.substringBefore(' ')
            ScreenManager.get(first)?.let {
                return it to trimmed.substringAfter(' ', "").trim()
            }
        }
        val screen = implicitScreen(source) ?: return null
        return screen to trimmed
    }

    private fun implicitScreen(source: CommandSourceStack): ManagedScreen? {
        ScreenManager.single()?.let { return it }
        if (ScreenManager.all().isEmpty()) {
            source.sendFailure(Component.literal("No screens defined yet. /pm wand, then /pm define <name>"))
            return null
        }
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Multiple screens exist; name one: /pm <command> <screen> ..."))
            return null
        }
        val nearest = ScreenManager.nearestTo(source.level.dimension().identifier().toString(), source.position)
        if (nearest == null) {
            source.sendFailure(Component.literal("No screens in this dimension. /pm list"))
        }
        return nearest
    }
}
