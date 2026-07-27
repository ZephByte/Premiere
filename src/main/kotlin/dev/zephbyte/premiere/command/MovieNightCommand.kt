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

    private val MOVIE_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(dev.zephbyte.premiere.upload.MovieLibrary.suggestions(), builder)
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("movienight")
                    .then(
                        Commands.literal("wand")
                            .requires(MoviePerms::canControl)
                            .executes(::wand)
                    )
                    .then(
                        Commands.literal("define")
                            .requires(MoviePerms::canControl)
                            .then(
                                Commands.argument("screen", StringArgumentType.word())
                                    .executes(::defineFromSelection)
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
                                screenArg()
                                    .executes(::playLoaded)
                                    .then(
                                        Commands.argument("movie", StringArgumentType.greedyString())
                                            .suggests(MOVIE_SUGGESTIONS)
                                            .executes(::play)
                                    )
                            )
                    )
                    .then(
                        Commands.literal("load")
                            .requires(MoviePerms::canControl)
                            .then(
                                screenArg().then(
                                    Commands.argument("movie", StringArgumentType.greedyString())
                                        .suggests(MOVIE_SUGGESTIONS)
                                        .executes(::load)
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
                    .then(
                        Commands.literal("upload")
                            .requires(MoviePerms::canControl)
                            .executes(::upload)
                    )
                    .then(
                        Commands.literal("movies")
                            .requires(MoviePerms::canControl)
                            .executes(::movies)
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

    private fun wand(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Only players can use the selection wand"))
            return 0
        }
        val on = dev.zephbyte.premiere.screen.SelectionTool.toggle(player)
        source.sendSuccess({
            Component.literal(
                if (on) {
                    "Selection mode ON: left-click one corner of the wall, right-click the opposite corner, then /movienight define <name>. Run /movienight wand again to cancel."
                } else {
                    "Selection mode off"
                }
            )
        }, false)
        return 1
    }

    private fun define(context: CommandContext<CommandSourceStack>): Int {
        val corner1 = BlockPosArgument.getBlockPos(context, "corner1")
        val corner2 = BlockPosArgument.getBlockPos(context, "corner2")
        return defineScreen(context, corner1, corner2, context.source.level.dimension().identifier().toString())
    }

    private fun defineFromSelection(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Console must pass corners: /movienight define <name> <corner1> <corner2>"))
            return 0
        }
        val selection = dev.zephbyte.premiere.screen.SelectionTool.selectionOf(player)
        val corner1 = selection?.corner1
        val corner2 = selection?.corner2
        if (selection == null || corner1 == null || corner2 == null) {
            source.sendFailure(
                Component.literal("No selection. Run /movienight wand and click both corners first (or pass coordinates).")
            )
            return 0
        }
        val result = defineScreen(context, corner1, corner2, selection.dimension ?: "")
        if (result == 1) dev.zephbyte.premiere.screen.SelectionTool.clear(player)
        return result
    }

    private fun defineScreen(
        context: CommandContext<CommandSourceStack>,
        corner1: net.minecraft.core.BlockPos,
        corner2: net.minecraft.core.BlockPos,
        dimension: String,
    ): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "screen")
        if (ScreenManager.get(name) != null) {
            source.sendFailure(Component.literal("Screen '$name' already exists"))
            return 0
        }
        if (dimension != source.level.dimension().identifier().toString()) {
            source.sendFailure(Component.literal("Selection is in another dimension; reselect the wall here"))
            return 0
        }
        val definition = try {
            ScreenDefinition.fromCorners(name, dimension, corner1, corner2, source.position)
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

    /**
     * Resolves a library name (against the bucket, presigned) or a pasted
     * public URL, then runs [onResolved] on the server thread. Resolution,
     * DNS checks, and signing all block, so they run off-thread.
     */
    private fun resolveMedia(
        source: CommandSourceStack,
        input: String,
        onResolved: (url: String, label: String, subtitleUrl: String) -> Unit,
    ) {
        val server = source.server

        fun fail(message: String) = server.execute { source.sendFailure(Component.literal(message)) }

        Thread.startVirtualThread {
            val isUrl = input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true)
            val url: String
            val label: String
            var subtitleUrl = ""
            if (isUrl) {
                val error = MediaUrls.validate(input) ?: MediaUrls.validateResolved(input)
                if (error != null) {
                    fail(error)
                    return@startVirtualThread
                }
                url = input
                label = input
                subtitleUrl = probeSidecarSubtitles(input)
            } else {
                if (!dev.zephbyte.premiere.PremiereConfig.uploadConfigured) {
                    fail("No movie library configured (r2_* settings in config/premiere.json); paste a URL instead.")
                    return@startVirtualThread
                }
                val key = try {
                    dev.zephbyte.premiere.upload.MovieLibrary.resolve(input)
                } catch (e: Exception) {
                    fail("Could not reach the movie library: ${e.message}")
                    return@startVirtualThread
                }
                if (key == null) {
                    fail("No movie named '$input'. See /movienight movies, or upload with /movienight upload.")
                    return@startVirtualThread
                }
                url = dev.zephbyte.premiere.upload.R2Storage.presignGet(key)
                label = dev.zephbyte.premiere.upload.MovieLibrary.displayName(key)
                subtitleUrl = dev.zephbyte.premiere.upload.MovieLibrary.subtitleKeyFor(key)
                    ?.let { dev.zephbyte.premiere.upload.R2Storage.presignGet(it) } ?: ""
            }
            server.execute { onResolved(url, label, subtitleUrl) }
        }
    }

    /** For pasted URLs: check whether `<url minus extension>.srt` exists. */
    private fun probeSidecarSubtitles(videoUrl: String): String {
        val candidate = videoUrl.substringBeforeLast('?').substringBeforeLast('.') + ".srt"
        if (MediaUrls.validate(candidate) != null) return ""
        return try {
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build()
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI(candidate))
                .timeout(java.time.Duration.ofSeconds(5))
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
            val status = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status in 200..299) candidate else ""
        } catch (e: Exception) {
            ""
        }
    }

    private val AUDIO_FLAG = Regex("""\s+--audio\s+([A-Za-z]{2,3})\s*$""")

    /** Splits a trailing `--audio <lang>` off the movie argument. */
    private fun parseAudioFlag(input: String): Pair<String, String> {
        val match = AUDIO_FLAG.find(input) ?: return input to ""
        return input.removeRange(match.range).trim() to match.groupValues[1].lowercase()
    }

    private fun play(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val screen = requireScreen(context) ?: return 0
        val (input, audioLanguage) = parseAudioFlag(StringArgumentType.getString(context, "movie").trim())
        resolveMedia(source, input) { url, label, subtitleUrl ->
            ScreenManager.play(source.server, screen, url, label, subtitleUrl, audioLanguage)
            val notes = buildString {
                if (subtitleUrl.isNotEmpty()) append(" (subtitles available)")
                if (audioLanguage.isNotEmpty()) append(" [audio: $audioLanguage]")
            }
            source.sendSuccess({ Component.literal("Playing '$label' on '${screen.definition.name}'$notes") }, true)
        }
        return 1
    }

    private fun load(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val screen = requireScreen(context) ?: return 0
        val (input, audioLanguage) = parseAudioFlag(StringArgumentType.getString(context, "movie").trim())
        resolveMedia(source, input) { url, label, subtitleUrl ->
            ScreenManager.load(source.server, screen, url, label, subtitleUrl, audioLanguage, source.player)
            source.sendSuccess({
                Component.literal(
                    "Loading '$label' on '${screen.definition.name}' — you'll get a ping when it's buffered, then /movienight play ${screen.definition.name}"
                )
            }, true)
        }
        return 1
    }

    /** Bare `/movienight play <screen>`: roll a loaded film (or resume a paused one). */
    private fun playLoaded(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val screen = requireScreen(context) ?: return 0
        return when (screen.playback.state) {
            PlayState.LOADED, PlayState.PAUSED -> {
                ScreenManager.start(source.server, screen)
                source.sendSuccess({ Component.literal("Rolling '${screen.playback.label}' on '${screen.definition.name}'") }, true)
                1
            }
            PlayState.PLAYING -> {
                source.sendFailure(Component.literal("'${screen.definition.name}' is already playing"))
                0
            }
            PlayState.STOPPED -> {
                source.sendFailure(Component.literal("Nothing loaded. Use /movienight load <screen> <movie> first, or play <screen> <movie>."))
                0
            }
        }
    }

    private fun movies(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!dev.zephbyte.premiere.PremiereConfig.uploadConfigured) {
            source.sendFailure(Component.literal("No movie library configured (r2_* settings in config/premiere.json)."))
            return 0
        }
        val server = source.server
        Thread.startVirtualThread {
            val names = try {
                val keys = dev.zephbyte.premiere.upload.R2Storage.listKeys()
                val library = dev.zephbyte.premiere.upload.MovieLibrary
                val subtitleNames = keys.filter(library::isSubtitle).map { library.displayName(it).lowercase() }.toSet()
                keys.filterNot(library::isSubtitle).map {
                    val name = library.displayName(it)
                    if (name.lowercase() in subtitleNames) "$name (cc)" else name
                }
            } catch (e: Exception) {
                server.execute { source.sendFailure(Component.literal("Could not reach the movie library: ${e.message}")) }
                return@startVirtualThread
            }
            server.execute {
                if (names.isEmpty()) {
                    source.sendSuccess({ Component.literal("The library is empty. Add movies with /movienight upload.") }, false)
                } else {
                    source.sendSuccess({ Component.literal("Movies: ${names.joinToString(", ")}") }, false)
                }
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
        if (screen.playback.state == PlayState.LOADED) {
            context.source.sendFailure(Component.literal("'${screen.definition.name}' is loaded, not playing — /movienight play ${screen.definition.name} to roll"))
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

    private fun upload(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!dev.zephbyte.premiere.PremiereConfig.uploadConfigured) {
            source.sendFailure(
                Component.literal(
                    "Uploads aren't configured. Fill in the r2_* settings in config/premiere.json (see README)."
                )
            )
            return 0
        }
        val token = dev.zephbyte.premiere.upload.UploadServer.mintToken()
        if (token == null) {
            source.sendFailure(Component.literal("Could not start the dashboard; check the server log."))
            return 0
        }
        val base = dev.zephbyte.premiere.PremiereConfig.uploadPublicAddress.ifBlank {
            "http://<this-server's-address>:${dev.zephbyte.premiere.PremiereConfig.uploadHttpPort}"
        }
        val url = "$base/dash?token=$token"
        val link = Component.literal(url).withStyle { style ->
            runCatching { style.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI(url))) }
                .getOrDefault(style)
                .withUnderlined(true)
        }
        source.sendSuccess({
            Component.literal("Dashboard link (valid 1 hour): ").append(link)
        }, false)
        if (dev.zephbyte.premiere.PremiereConfig.uploadPublicAddress.isBlank()) {
            source.sendSuccess({
                Component.literal("Tip: set upload_public_address in config/premiere.json to make this link clickable.")
            }, false)
        }
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
                PlayState.LOADED -> "loaded and ready: ${p.label}"
                PlayState.PAUSED -> "paused at ${p.currentPositionMs() / 1000}s: ${p.label}"
                PlayState.PLAYING -> "playing at ${p.currentPositionMs() / 1000}s: ${p.label}"
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
