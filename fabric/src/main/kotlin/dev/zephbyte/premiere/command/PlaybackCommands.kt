package dev.zephbyte.premiere.command

import com.mojang.brigadier.context.CommandContext
import dev.zephbyte.premiere.command.PremiereCommand.targetAndRest
import dev.zephbyte.premiere.screen.ManagedScreen
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.upload.MediaResolver
import dev.zephbyte.premiere.command.PlayArgs.parseAudioFlag
import dev.zephbyte.premiere.util.Times
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

/**
 * Playback control. Every command here accepts an optional leading screen
 * name (see [PremiereCommand.targetAndRest]).
 */
internal object PlaybackCommands {


    fun play(context: CommandContext<CommandSourceStack>, args: String): Int {
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
            source.sendSuccess({
                Component.literal("Playing '${resolved.label}' on '${screen.definition.name}'$notes")
            }, true)
        }
        return 1
    }

    fun load(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            source.sendFailure(Component.literal("Usage: /pm load [screen] <movie|url>"))
            return 0
        }
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            ScreenManager.load(
                screen,
                resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage,
                source.player?.uuid,
            )
            source.sendSuccess({
                Component.literal(
                    "Loading '${resolved.label}' on '${screen.definition.name}' — you'll get a ping when it's buffered, then /pm play"
                )
            }, true)
        }
        return 1
    }

    fun pause(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        when (screen.playback.state) {
            PlayState.STOPPED -> {
                source.sendFailure(Component.literal("Nothing is playing on '${screen.definition.name}'"))
                return 0
            }
            PlayState.LOADED -> {
                source.sendFailure(
                    Component.literal("'${screen.definition.name}' is loaded, not playing — /pm play to roll")
                )
                return 0
            }
            else -> {}
        }
        val playing = ScreenManager.togglePause(screen)
        val verb = if (playing) "Resumed" else "Paused"
        source.sendSuccess({ Component.literal("$verb '${screen.definition.name}'") }, true)
        return 1
    }

    fun stop(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        ScreenManager.stop(screen)
        source.sendSuccess({ Component.literal("Stopped '${screen.definition.name}'") }, true)
        return 1
    }

    fun seek(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (screen.playback.state == PlayState.STOPPED) {
            source.sendFailure(Component.literal("Nothing is playing on '${screen.definition.name}'"))
            return 0
        }
        if (rest.isEmpty()) {
            source.sendFailure(Component.literal("Usage: /pm seek [screen] <time> — 1:23:45, 5:30, 90, +30, -1:30"))
            return 0
        }
        val target = Times.parseMs(rest, screen.playback.currentPositionMs())
        if (target == null) {
            source.sendFailure(Component.literal("Can't read '$rest'. Use 1:23:45, 5:30, 90, +30, or -1:30."))
            return 0
        }
        ScreenManager.seek(screen, target)
        source.sendSuccess({
            Component.literal(
                "'${screen.playback.label}' seeked to ${Times.format(screen.playback.currentPositionMs())}"
            )
        }, true)
        return 1
    }

    fun volume(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        val volume = rest.toIntOrNull()?.takeIf { it in 0..100 }
        if (volume == null) {
            source.sendFailure(Component.literal("Usage: /pm volume [screen] <0-100>"))
            return 0
        }
        ScreenManager.setVolume(screen, volume / 100f)
        source.sendSuccess({ Component.literal("Volume on '${screen.definition.name}' set to $volume%") }, true)
        return 1
    }

    /** Bare play: roll a loaded film (or resume a paused one). */
    private fun roll(source: CommandSourceStack, screen: ManagedScreen): Int {
        return when (screen.playback.state) {
            PlayState.LOADED, PlayState.PAUSED -> {
                ScreenManager.start(screen)
                source.sendSuccess({
                    Component.literal("Rolling '${screen.playback.label}' on '${screen.definition.name}'")
                }, true)
                1
            }
            PlayState.PLAYING -> {
                source.sendFailure(Component.literal("'${screen.definition.name}' is already playing"))
                0
            }
            PlayState.STOPPED -> {
                source.sendFailure(
                    Component.literal("Nothing loaded. Use /pm load <movie> first, or /pm play <movie>.")
                )
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
        val server = source.server
        Thread.startVirtualThread {
            val resolved = try {
                MediaResolver.resolve(input)
            } catch (e: Exception) {
                server.execute { source.sendFailure(Component.literal(e.message ?: "Could not resolve that")) }
                return@startVirtualThread
            }
            server.execute { onResolved(resolved) }
        }
    }
}
