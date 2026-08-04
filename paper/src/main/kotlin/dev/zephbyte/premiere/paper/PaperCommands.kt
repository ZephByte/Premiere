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
import dev.zephbyte.premiere.screen.QueuedMedia
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
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * The Paper command tree — same commands, same wording as the Fabric side;
 * all real logic lives in :common (ScreenTargeting, ConfirmTracker, PlayArgs,
 * ScreenManager). Permission gating uses Bukkit's permission system, which
 * LuckPerms plugs into natively — no LuckPerms API needed here.
 */
object PaperCommands {

    const val CONTROL_NODE = "premiere.control"
    const val LEGACY_CONTROL_NODE = "movienight.control"

    private val overwriteConfirms = ConfirmTracker()
    private lateinit var plugin: PremierePaperPlugin

    fun register(plugin: PremierePaperPlugin, registrar: io.papermc.paper.command.brigadier.Commands) {
        this.plugin = plugin
        // /premiere is canonical (it's the mod's name), /pm is the short
        // everyday form, /movienight survives for muscle memory.
        registrar.register(buildTree("premiere").build(), "Premiere movie screens", listOf("pm", "movienight"))
    }

    fun hasControlPermission(sender: CommandSender): Boolean =
        sender.hasPermission(CONTROL_NODE) || sender.hasPermission(LEGACY_CONTROL_NODE)

    private fun canControl(source: CommandSourceStack): Boolean = hasControlPermission(source.sender)

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
                    .then(screenArg().executes { play(it, StringArgumentType.getString(it, "screen")) })
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
                Commands.literal("queue")
                    .requires(::canControl)
                    .executes { queue(it, "") }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests { _, b -> queueSuggestions(b) }
                            .executes { queue(it, StringArgumentType.getString(it, "args")) }
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
            .then(
                Commands.literal("radius")
                    .requires(::canControl)
                    .executes { radius(it, "") }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .suggests { _, b ->
                                val wordStart = b.remaining.lastIndexOf(' ') + 1
                                val options = if (wordStart == 0) screenNames() + "default" else listOf("default")
                                suggest(b.createOffset(b.start + wordStart), options)
                            }
                            .executes { radius(it, StringArgumentType.getString(it, "args")) }
                    )
            )
            .then(Commands.literal("dashboard").requires(::canControl).executes(::dashboard))
            .then(Commands.literal("dash").requires(::canControl).executes(::dashboard))
            .then(Commands.literal("movies").requires(::canControl).executes(::movies))
            .then(Commands.literal("reload").requires(::canControl).executes(::reload))
            .then(Commands.literal("list").executes(::list))

    private fun screenArg() =
        Commands.argument("screen", StringArgumentType.word())
            .suggests { _, b -> suggest(b, screenNames()) }

    private fun screenNames() = ScreenManager.all().map { it.definition.name }

    private fun screenAndMovieNames() = screenNames() + MovieLibrary.suggestions()

    private fun queueSuggestions(builder: SuggestionsBuilder): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val first = builder.remaining.substringBefore(' ')
        val explicitScreen = builder.remaining.contains(' ') && ScreenManager.get(first) != null
        val options = MovieLibrary.suggestions() + listOf("clear", "remove")
        return if (explicitScreen) {
            suggest(builder.createOffset(builder.start + first.length + 1), options)
        } else {
            suggest(builder, screenNames() + options)
        }
    }

    private fun suggest(builder: SuggestionsBuilder, options: List<String>): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val remaining = builder.remainingLowerCase
        options.filter { it.lowercase().startsWith(remaining) }.forEach(builder::suggest)
        return builder.buildFuture()
    }

    // --- messaging (same wording as the Fabric side) ---

    private fun prefix(): Component = Component.text("Premiere ", NamedTextColor.DARK_PURPLE)
        .decorate(TextDecoration.BOLD)

    private fun success(text: String): Component = prefix()
        .append(Component.text("✓ ", NamedTextColor.GREEN))
        .append(Component.text(text, NamedTextColor.GRAY))

    private fun info(text: String): Component = prefix()
        .append(Component.text("• ", NamedTextColor.AQUA))
        .append(Component.text(text, NamedTextColor.GRAY))

    private fun error(text: String): Component = prefix()
        .append(Component.text("✕ ", NamedTextColor.RED))
        .append(Component.text(text, NamedTextColor.RED))

    private fun command(label: String, command: String): Component = Component.text(label, NamedTextColor.AQUA)
        .decorate(TextDecoration.UNDERLINED)
        .clickEvent(ClickEvent.suggestCommand(command))

    private fun ok(source: CommandSourceStack, text: String) = source.sender.sendMessage(success(text))

    private fun note(source: CommandSourceStack, text: String) = source.sender.sendMessage(info(text))

    private fun fail(source: CommandSourceStack, text: String) = source.sender.sendMessage(error(text))

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
            fail(source, "The selection wand can only be used in-game.")
            return 0
        }
        val on = SelectionTool.toggle(player.uniqueId)
        ok(
            source,
            if (on) {
                "Selection mode enabled. Left-click one wall corner, right-click the opposite corner, then run /pm define <name>."
            } else {
                "Selection mode disabled."
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
            fail(source, "No complete selection. Run /pm wand and click both wall corners first.")
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
        if (dimension != source.location.world.key.toString()) {
            fail(source, "That selection is in another dimension. Select the wall again here.")
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
        val replacing = ScreenManager.get(name) != null
        if (replacing && !confirmOverwrite(source, name)) return 0
        val saved = if (replacing) ScreenManager.redefine(definition) else ScreenManager.define(definition)
        if (!saved) {
            fail(source, "Couldn't save $name. The previous screen is unchanged; check the server log.")
            return 0
        }
        ok(source, "Defined $name — ${definition.width}×${definition.height}, facing ${definition.facing.serializedName}.")
        return 1
    }

    private fun confirmOverwrite(source: CommandSourceStack, name: String): Boolean {
        if (!overwriteConfirms.confirm(source.sender.name, name)) {
            fail(source, "$name already exists. Repeat the command within 30 seconds to replace it.")
            return false
        }
        return true
    }

    private fun undefine(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        val screen = ScreenManager.get(name)
        if (screen == null) {
            fail(source, "No screen is named $name. Run /pm list to see defined screens.")
            return 0
        }
        if (!ScreenManager.undefine(screen.definition.name)) {
            fail(source, "Couldn't remove $name. It is unchanged; check the server log.")
            return 0
        }
        ok(source, "Removed screen ${screen.definition.name}.")
        return 1
    }

    // --- playback ---

    private fun play(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isNotEmpty()) {
            fail(source, "Movies must be prepared first. Use /pm load [screen] <movie or URL>, then /pm play when it is ready.")
            return 0
        }
        return roll(source, screen)
    }

    private fun load(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            fail(source, "Choose a movie: /pm load [screen] <movie or URL>")
            return 0
        }
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            ScreenManager.load(
                screen,
                resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage,
                playerOf(source)?.uniqueId,
            )
            ok(source, "Preparing ${resolved.label} on ${screen.definition.name}. You'll be notified when the first frame is ready.")
        }
        return 1
    }

    private fun queue(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            if (screen.queue.isEmpty()) {
                note(source, "${screen.definition.name}'s queue is empty.")
                return 1
            }
            source.sender.sendMessage(info("Up next on ${screen.definition.name} — ${screen.queue.size} queued"))
            screen.queue.forEachIndexed { index, media ->
                source.sender.sendMessage(
                    Component.text("  ${index + 1}. ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(media.label, NamedTextColor.WHITE))
                        .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                        .append(command("[remove]", "/pm queue ${screen.definition.name} remove ${index + 1}"))
                )
            }
            return screen.queue.size
        }
        if (rest.equals("clear", ignoreCase = true)) {
            val count = ScreenManager.clearQueue(screen)
            ok(source, "Cleared $count queued ${if (count == 1) "movie" else "movies"} from ${screen.definition.name}.")
            return 1
        }
        if (rest.startsWith("remove ", ignoreCase = true)) {
            val index = rest.substringAfter(' ').trim().toIntOrNull()
            val removed = index?.let { ScreenManager.removeQueued(screen, it - 1) }
            if (removed == null) {
                fail(source, "Choose a queue number from 1 to ${screen.queue.size}.")
                return 0
            }
            ok(source, "Removed ${removed.label} from ${screen.definition.name}'s queue.")
            return 1
        }
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            val media = QueuedMedia(resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage)
            val position = ScreenManager.enqueue(screen, media)
            if (screen.playback.state == PlayState.STOPPED) {
                ScreenManager.loadNext(screen, playerOf(source)?.uniqueId)
                ok(
                    source,
                    "Preparing ${screen.playback.label} on ${screen.definition.name}; it will roll when viewers are ready and ${screen.queue.size} remain queued.",
                )
            } else {
                ok(source, "Queued ${resolved.label} as #$position on ${screen.definition.name}.")
            }
        }
        return 1
    }

    private fun pause(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        when (screen.playback.state) {
            PlayState.STOPPED -> {
                fail(source, "Nothing is playing on ${screen.definition.name}.")
                return 0
            }
            PlayState.LOADED -> {
                fail(source, "${screen.definition.name} is ready but has not started. Run /pm play to roll.")
                return 0
            }
            else -> {}
        }
        val playing = ScreenManager.togglePause(screen)
        ok(source, "${if (playing) "Resumed" else "Paused"} ${screen.definition.name}.")
        return 1
    }

    private fun stopCmd(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        ScreenManager.stop(screen)
        ok(source, "Stopped playback on ${screen.definition.name}.")
        return 1
    }

    private fun seek(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (screen.playback.state == PlayState.STOPPED) {
            fail(source, "Nothing is playing on ${screen.definition.name}.")
            return 0
        }
        if (rest.isEmpty()) {
            fail(source, "Choose a time: /pm seek [screen] <1:23:45, 5:30, +30, or -1:30>")
            return 0
        }
        val target = Times.parseMs(rest, screen.playback.currentPositionMs())
        if (target == null) {
            fail(source, "Couldn't read '$rest'. Try 1:23:45, 5:30, 90, +30, or -1:30.")
            return 0
        }
        ScreenManager.seek(screen, target)
        ok(source, "Moved ${screen.playback.label} to ${Times.format(screen.playback.currentPositionMs())}.")
        return 1
    }

    private fun volume(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        val volume = rest.toIntOrNull()?.takeIf { it in 0..100 }
        if (volume == null) {
            fail(source, "Volume must be from 0 to 100: /pm volume [screen] <0-100>")
            return 0
        }
        ScreenManager.setVolume(screen, volume / 100f)
        ok(source, "Set ${screen.definition.name} theater volume to $volume%.")
        return 1
    }

    private fun radius(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            val origin = if (screen.audioFullVolumeRadiusOverride == null) "config default" else "screen override"
            note(
                source,
                "${screen.definition.name} has a ${blocks(screen.effectiveAudioFullVolumeRadius())}-block full-volume audience radius ($origin).",
            )
            return 1
        }
        if (rest.equals("default", ignoreCase = true)) {
            if (!ScreenManager.setAudioFullVolumeRadius(screen, null)) {
                fail(source, "Couldn't save ${screen.definition.name}; its audience radius is unchanged.")
                return 0
            }
            ok(
                source,
                "${screen.definition.name} now follows the ${blocks(PremiereConfig.audioFullVolumeRadius)}-block config default.",
            )
            return 1
        }
        val radius = rest.toFloatOrNull()?.takeIf {
            it.isFinite() && it >= 0f && it < PremiereConfig.audioDistance
        }
        if (radius == null) {
            fail(
                source,
                "Radius must be at least 0 and below ${blocks(PremiereConfig.audioDistance)} blocks, or 'default'.",
            )
            return 0
        }
        if (!ScreenManager.setAudioFullVolumeRadius(screen, radius)) {
            fail(source, "Couldn't save ${screen.definition.name}; its audience radius is unchanged.")
            return 0
        }
        ok(source, "Set ${screen.definition.name}'s full-volume audience radius to ${blocks(radius)} blocks.")
        return 1
    }

    private fun blocks(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

    /** Bare play: roll a loaded film (or resume a paused one). */
    private fun roll(source: CommandSourceStack, screen: ManagedScreen): Int {
        return when (screen.playback.state) {
            PlayState.LOADED -> {
                if (!ScreenManager.start(screen)) {
                    fail(source, "${screen.playback.label} is still buffering on viewers' clients.")
                    return 0
                }
                ok(source, "Rolling ${screen.playback.label} on ${screen.definition.name}.")
                1
            }
            PlayState.PAUSED -> {
                ScreenManager.start(screen)
                ok(source, "Resumed ${screen.playback.label} on ${screen.definition.name}.")
                1
            }
            PlayState.PLAYING -> {
                fail(source, "${screen.definition.name} is already playing.")
                0
            }
            PlayState.STOPPED -> {
                fail(source, "Nothing is loaded. Use /pm load [screen] <movie or URL> first.")
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
                    fail(source, e.message ?: "Could not resolve that movie.")
                })
                return@startVirtualThread
            }
            Bukkit.getScheduler().runTask(plugin, Runnable { onResolved(resolved) })
        }
    }

    // --- admin ---

    private fun dashboard(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            fail(source, "The dashboard needs the R2 settings in plugins/Premiere/premiere.json.")
            return 0
        }
        val token = UploadServer.mintToken()
        if (token == null) {
            fail(source, "Couldn't start the dashboard. Check the server log for the port error.")
            return 0
        }
        val base = UploadServer.dashboardBaseAddress()
        val url = "$base/dash?token=$token"
        val link = Component.text("[ OPEN DASHBOARD ]", NamedTextColor.AQUA)
            .decorate(TextDecoration.BOLD)
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(url))
        source.sender.sendMessage(info("Staff dashboard  ").append(link))
        note(source, "Private session link • expires in 1 hour.")
        if (!UploadServer.hasPublicAddress) {
            note(
                source,
                "This automatic link works on the server's local network. For remote staff, set upload_public_address to an HTTPS proxy or tunnel URL.",
            )
        }
        return 1
    }

    private fun movies(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            fail(source, "No movie library is configured. Add the R2 settings in plugins/Premiere/premiere.json.")
            return 0
        }
        Thread.startVirtualThread {
            val names = try {
                MovieLibrary.displayNamesWithCc(R2Storage.listKeys())
            } catch (e: Exception) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    fail(source, "Couldn't reach the movie library: ${e.message}")
                })
                return@startVirtualThread
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (names.isEmpty()) {
                    note(source, "The movie library is empty. Add a movie through /pm dashboard.")
                } else {
                    source.sender.sendMessage(info("Movie library — ${names.size} titles"))
                    names.forEach { displayName ->
                        val movie = displayName.removeSuffix(" (cc)")
                        source.sender.sendMessage(
                            Component.text("  • ", NamedTextColor.DARK_GRAY)
                                .append(command(displayName, "/pm load $movie"))
                        )
                    }
                }
            })
        }
        return 1
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        if (!PremiereConfig.reload()) {
            fail(context.source, "Config reload failed. Fix the server-log error; the current settings are still active.")
            return 0
        }
        // The dashboard restarts lazily so a port change takes effect; open
        // sessions lose their tokens, which is fine.
        UploadServer.stop()
        ok(context.source, "Config reloaded. New audio settings apply to the next movie.")
        context.source.sender.sendMessage(
            info("Dashboard sessions were refreshed. ")
                .append(command("Run /pm dashboard", "/pm dashboard"))
                .append(Component.text(" for a new link.", NamedTextColor.DARK_GRAY))
        )
        return 1
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val screens = ScreenManager.all()
        if (screens.isEmpty()) {
            note(source, "No screens are defined. Use /pm wand to create one.")
            return 0
        }
        source.sender.sendMessage(info("Screens — ${screens.size} defined"))
        for (screen in screens) {
            val d = screen.definition
            val p = screen.playback
            val (status, color) = when (p.state) {
                PlayState.STOPPED -> "IDLE" to NamedTextColor.DARK_GRAY
                PlayState.LOADED -> "READY" to NamedTextColor.AQUA
                PlayState.PAUSED -> "PAUSED" to NamedTextColor.YELLOW
                PlayState.PLAYING -> "PLAYING" to NamedTextColor.GREEN
            }
            var line = Component.text("  ● ", color)
                .append(Component.text(d.name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text("  $status", color))
                .append(Component.text("  ${d.width}×${d.height} ${d.facing.serializedName}", NamedTextColor.GRAY))
                .append(
                    Component.text(
                        "  full audio ${blocks(screen.effectiveAudioFullVolumeRadius())}b" +
                            if (screen.audioFullVolumeRadiusOverride == null) " default" else " custom",
                        NamedTextColor.DARK_GRAY,
                    ),
                )
                .append(
                    if (screen.queue.isEmpty()) Component.empty()
                    else Component.text("  ${screen.queue.size} queued", NamedTextColor.AQUA),
                )
            if (p.state != PlayState.STOPPED) {
                line = line.append(
                    Component.text("  ${Times.format(p.currentPositionMs())} • ${p.label}", NamedTextColor.DARK_GRAY)
                )
            }
            source.sender.sendMessage(line)
        }
        return screens.size
    }
}
