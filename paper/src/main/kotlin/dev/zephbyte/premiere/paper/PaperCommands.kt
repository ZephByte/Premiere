package dev.zephbyte.premiere.paper

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.command.ConfirmTracker
import dev.zephbyte.premiere.command.PlayArgs.parseAudioFlag
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.screen.ManagedScreen
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.screen.ScreenTargeting
import dev.zephbyte.premiere.screen.SelectionTool
import dev.zephbyte.premiere.upload.MediaResolver
import dev.zephbyte.premiere.upload.MovieLibrary
import dev.zephbyte.premiere.upload.R2Storage
import dev.zephbyte.premiere.upload.UploadServer
import dev.zephbyte.premiere.util.Times
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * The Paper command tree — same commands, same wording as the Fabric side;
 * all real logic lives in :common (ScreenTargeting, ConfirmTracker, PlayArgs,
 * ScreenManager). Permission gating uses Bukkit's permission system, which
 * LuckPerms plugs into natively — no LuckPerms API needed here.
 */
object PaperCommands {

    const val CONTROL_NODE = "movienight.control"

    private val overwriteConfirms = ConfirmTracker()
    private lateinit var plugin: PremierePaperPlugin

    fun register(plugin: PremierePaperPlugin, registrar: io.papermc.paper.command.brigadier.Commands) {
        this.plugin = plugin
        // /premiere is canonical (it's the mod's name), /pm is the short
        // everyday form, /movienight survives for muscle memory.
        registrar.register(buildTree("premiere").build(), "Premiere movie screens", listOf("pm", "movienight"))
    }

    private fun canControl(source: CommandSourceStack): Boolean =
        source.sender.hasPermission(CONTROL_NODE)

    private fun buildTree(root: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(root)
            .then(
                Commands.literal("wand")
                    .requires(::canControl)
                    .executes(::wand)
            )
            .then(
                Commands.literal("define")
                    .requires(::canControl)
                    .then(
                        Commands.argument("screen", StringArgumentType.word())
                            .executes(::defineFromSelection)
                            .then(
                                Commands.argument("corner1", ArgumentTypes.blockPosition())
                                    .then(
                                        Commands.argument("corner2", ArgumentTypes.blockPosition())
                                            .executes(::defineFromCorners)
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("undefine")
                    .requires(::canControl)
                    .then(screenArg().executes(::undefine))
            )
            .then(
                Commands.literal("play")
                    .requires(::canControl)
                    .executes { play(it, "") }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests { _, b -> suggest(b, screenAndMovieNames()) }
                            .executes { play(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("load")
                    .requires(::canControl)
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests { _, b -> suggest(b, screenAndMovieNames()) }
                            .executes { load(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("pause")
                    .requires(::canControl)
                    .executes { pause(it, "") }
                    .then(screenArg().executes { pause(it, StringArgumentType.getString(it, "screen")) })
            )
            .then(
                Commands.literal("stop")
                    .requires(::canControl)
                    .executes { stopCmd(it, "") }
                    .then(screenArg().executes { stopCmd(it, StringArgumentType.getString(it, "screen")) })
            )
            .then(
                Commands.literal("seek")
                    .requires(::canControl)
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests { _, b -> suggest(b, screenNames()) }
                            .executes { seek(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(
                Commands.literal("volume")
                    .requires(::canControl)
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests { _, b -> suggest(b, screenNames()) }
                            .executes { volume(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(Commands.literal("upload").requires(::canControl).executes(::upload))
            .then(Commands.literal("movies").requires(::canControl).executes(::movies))
            .then(Commands.literal("reload").requires(::canControl).executes(::reload))
            .then(Commands.literal("list").executes(::list))

    private fun screenArg() =
        Commands.argument("screen", StringArgumentType.word())
            .suggests { _, b -> suggest(b, screenNames()) }

    private fun screenNames() = ScreenManager.all().map { it.definition.name }

    private fun screenAndMovieNames() = screenNames() + MovieLibrary.suggestions()

    private fun suggest(builder: SuggestionsBuilder, options: List<String>): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val remaining = builder.remainingLowerCase
        options.filter { it.lowercase().startsWith(remaining) }.forEach(builder::suggest)
        return builder.buildFuture()
    }

    // --- messaging (same wording as the Fabric side) ---

    private fun ok(source: CommandSourceStack, text: String) =
        source.sender.sendMessage(Component.text(text))

    private fun fail(source: CommandSourceStack, text: String) =
        source.sender.sendMessage(Component.text(text, NamedTextColor.RED))

    private fun playerOf(source: CommandSourceStack): Player? = source.executor as? Player

    /** Optional-leading-screen parsing (see ScreenTargeting in :common). */
    private fun targetAndRest(source: CommandSourceStack, rawArgs: String): Pair<ManagedScreen, String>? {
        val player = playerOf(source)
        val result = ScreenTargeting.resolve(
            rawArgs,
            player?.world?.key?.toString(),
            player?.location?.let { Vec3d(it.x, it.y, it.z) },
        )
        return when (result) {
            is ScreenTargeting.Result.Target -> result.screen to result.rest
            is ScreenTargeting.Result.Fail -> {
                fail(source, result.message)
                null
            }
        }
    }

    // --- screens ---

    private fun wand(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = playerOf(source)
        if (player == null) {
            fail(source, "Only players can use the selection wand")
            return 0
        }
        val on = SelectionTool.toggle(player.uniqueId)
        ok(
            source,
            if (on) {
                "Selection mode ON: left-click one corner of the wall, right-click the opposite corner, then /pm define <name>. Run /pm wand again to cancel."
            } else {
                "Selection mode off"
            }
        )
        return 1
    }

    private fun defineFromCorners(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val corner1 = context.getArgument("corner1", BlockPositionResolver::class.java).resolve(source)
        val corner2 = context.getArgument("corner2", BlockPositionResolver::class.java).resolve(source)
        return define(
            context,
            ScreenPos(corner1.blockX(), corner1.blockY(), corner1.blockZ()),
            ScreenPos(corner2.blockX(), corner2.blockY(), corner2.blockZ()),
            source.location.world.key.toString(),
        )
    }

    private fun defineFromSelection(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = playerOf(source)
        if (player == null) {
            fail(source, "Console must pass corners: /pm define <name> <corner1> <corner2>")
            return 0
        }
        val selection = SelectionTool.selectionOf(player.uniqueId)
        val corner1 = selection?.corner1
        val corner2 = selection?.corner2
        if (selection == null || corner1 == null || corner2 == null) {
            fail(source, "No selection. Run /pm wand and click both corners first (or pass coordinates).")
            return 0
        }
        val result = define(context, corner1, corner2, selection.dimension ?: "")
        if (result == 1) SelectionTool.clear(player.uniqueId)
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
        if (dimension != source.location.world.key.toString()) {
            fail(source, "Selection is in another dimension; reselect the wall here")
            return 0
        }
        val viewer = playerOf(source)?.location?.let { Vec3d(it.x, it.y, it.z) }
            ?: source.location.let { Vec3d(it.x, it.y, it.z) }
        val definition = try {
            ScreenDefinition.fromCorners(name, dimension, corner1, corner2, viewer)
        } catch (e: IllegalArgumentException) {
            fail(source, e.message ?: "Invalid screen corners")
            return 0
        }
        ScreenManager.define(definition)
        ok(source, "Defined screen '$name': ${definition.width}x${definition.height} facing ${definition.facing.serializedName}")
        return 1
    }

    private fun confirmOverwrite(source: CommandSourceStack, name: String): Boolean {
        if (!overwriteConfirms.confirm(source.sender.name, name)) {
            fail(source, "Screen '$name' already exists. Run the same command again within 30s to overwrite it.")
            return false
        }
        ScreenManager.undefine(name)
        return true
    }

    private fun undefine(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        val screen = ScreenManager.get(name)
        if (screen == null) {
            fail(source, "No screen named '$name'. See /pm list")
            return 0
        }
        ScreenManager.undefine(screen.definition.name)
        ok(source, "Removed screen '${screen.definition.name}'")
        return 1
    }

    // --- playback ---

    private fun play(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) return roll(source, screen)
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            ScreenManager.play(screen, resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage)
            val notes = buildString {
                if (resolved.subtitleUrl.isNotEmpty()) append(" (subtitles available)")
                if (audioLanguage.isNotEmpty()) append(" [audio: $audioLanguage]")
            }
            ok(source, "Playing '${resolved.label}' on '${screen.definition.name}'$notes")
        }
        return 1
    }

    private fun load(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            fail(source, "Usage: /pm load [screen] <movie|url>")
            return 0
        }
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            ScreenManager.load(
                screen,
                resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage,
                playerOf(source)?.uniqueId,
            )
            ok(source, "Loading '${resolved.label}' on '${screen.definition.name}' — you'll get a ping when it's buffered, then /pm play")
        }
        return 1
    }

    private fun pause(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        when (screen.playback.state) {
            PlayState.STOPPED -> {
                fail(source, "Nothing is playing on '${screen.definition.name}'")
                return 0
            }
            PlayState.LOADED -> {
                fail(source, "'${screen.definition.name}' is loaded, not playing — /pm play to roll")
                return 0
            }
            else -> {}
        }
        val playing = ScreenManager.togglePause(screen)
        ok(source, "${if (playing) "Resumed" else "Paused"} '${screen.definition.name}'")
        return 1
    }

    private fun stopCmd(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        ScreenManager.stop(screen)
        ok(source, "Stopped '${screen.definition.name}'")
        return 1
    }

    private fun seek(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (screen.playback.state == PlayState.STOPPED) {
            fail(source, "Nothing is playing on '${screen.definition.name}'")
            return 0
        }
        if (rest.isEmpty()) {
            fail(source, "Usage: /pm seek [screen] <time> — 1:23:45, 5:30, 90, +30, -1:30")
            return 0
        }
        val target = Times.parseMs(rest, screen.playback.currentPositionMs())
        if (target == null) {
            fail(source, "Can't read '$rest'. Use 1:23:45, 5:30, 90, +30, or -1:30.")
            return 0
        }
        ScreenManager.seek(screen, target)
        ok(source, "'${screen.playback.label}' seeked to ${Times.format(screen.playback.currentPositionMs())}")
        return 1
    }

    private fun volume(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        val volume = rest.toIntOrNull()?.takeIf { it in 0..100 }
        if (volume == null) {
            fail(source, "Usage: /pm volume [screen] <0-100>")
            return 0
        }
        ScreenManager.setVolume(screen, volume / 100f)
        ok(source, "Volume on '${screen.definition.name}' set to $volume%")
        return 1
    }

    /** Bare play: roll a loaded film (or resume a paused one). */
    private fun roll(source: CommandSourceStack, screen: ManagedScreen): Int {
        return when (screen.playback.state) {
            PlayState.LOADED, PlayState.PAUSED -> {
                ScreenManager.start(screen)
                ok(source, "Rolling '${screen.playback.label}' on '${screen.definition.name}'")
                1
            }
            PlayState.PLAYING -> {
                fail(source, "'${screen.definition.name}' is already playing")
                0
            }
            PlayState.STOPPED -> {
                fail(source, "Nothing loaded. Use /pm load <movie> first, or /pm play <movie>.")
                0
            }
        }
    }

    /**
     * Resolves a movie name/URL off-thread (see MediaResolver), then runs
     * [onResolved] back on the server thread.
     */
    private fun resolveMedia(
        source: CommandSourceStack,
        input: String,
        onResolved: (MediaResolver.Resolved) -> Unit,
    ) {
        Thread.startVirtualThread {
            val resolved = try {
                MediaResolver.resolve(input)
            } catch (e: Exception) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    fail(source, e.message ?: "Could not resolve that")
                })
                return@startVirtualThread
            }
            Bukkit.getScheduler().runTask(plugin, Runnable { onResolved(resolved) })
        }
    }

    // --- admin ---

    private fun upload(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            fail(source, "Uploads aren't configured. Fill in the r2_* settings in plugins/Premiere/premiere.json (see README).")
            return 0
        }
        val token = UploadServer.mintToken()
        if (token == null) {
            fail(source, "Could not start the dashboard; check the server log.")
            return 0
        }
        val base = PremiereConfig.uploadPublicAddress.ifBlank {
            "http://<this-server's-address>:${PremiereConfig.uploadHttpPort}"
        }
        val url = "$base/dash?token=$token"
        val link = Component.text(url)
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(url))
        source.sender.sendMessage(Component.text("Dashboard link (valid 1 hour): ").append(link))
        if (PremiereConfig.uploadPublicAddress.isBlank()) {
            ok(source, "Tip: set upload_public_address in plugins/Premiere/premiere.json to make this link clickable.")
        }
        return 1
    }

    private fun movies(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            fail(source, "No movie library configured (r2_* settings in plugins/Premiere/premiere.json).")
            return 0
        }
        Thread.startVirtualThread {
            val names = try {
                MovieLibrary.displayNamesWithCc(R2Storage.listKeys())
            } catch (e: Exception) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    fail(source, "Could not reach the movie library: ${e.message}")
                })
                return@startVirtualThread
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (names.isEmpty()) {
                    ok(source, "The library is empty. Add movies with /pm upload.")
                } else {
                    ok(source, "Movies: ${names.joinToString(", ")}")
                }
            })
        }
        return 1
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        PremiereConfig.reload()
        // The dashboard restarts lazily so a port change takes effect; open
        // sessions lose their tokens, which is fine.
        UploadServer.stop()
        ok(context.source, "Premiere config reloaded. Audio settings apply from the next play; run /pm upload for a fresh dashboard.")
        return 1
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val screens = ScreenManager.all()
        if (screens.isEmpty()) {
            ok(source, "No screens defined")
            return 0
        }
        for (screen in screens) {
            val d = screen.definition
            val p = screen.playback
            val status = when (p.state) {
                PlayState.STOPPED -> "idle"
                PlayState.LOADED -> "loaded and ready: ${p.label}"
                PlayState.PAUSED -> "paused at ${p.currentPositionMs() / 1000}s: ${p.label}"
                PlayState.PLAYING -> "playing at ${p.currentPositionMs() / 1000}s: ${p.label}"
            }
            ok(source, "${d.name}: ${d.width}x${d.height} at (${d.origin.x}, ${d.origin.y}, ${d.origin.z}) facing ${d.facing.serializedName}, $status")
        }
        return screens.size
    }
}
