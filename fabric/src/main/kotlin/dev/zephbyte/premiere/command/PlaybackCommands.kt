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
            CommandFeedback.sendSuccess(
                source,
                "Now playing ${resolved.label} on ${screen.definition.name}$notes",
            )
        }
        return 1
    }

    fun load(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            CommandFeedback.sendError(source, "Choose a movie: /pm load [screen] <movie or URL>")
            return 0
        }
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            ScreenManager.load(
                screen,
                resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage,
                source.player?.uuid,
            )
            CommandFeedback.sendSuccess(
                source,
                "Preparing ${resolved.label} on ${screen.definition.name}. You'll be notified when the first frame is ready.",
            )
        }
        return 1
    }

    fun pause(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        when (screen.playback.state) {
            PlayState.STOPPED -> {
                CommandFeedback.sendError(source, "Nothing is playing on ${screen.definition.name}.")
                return 0
            }
            PlayState.LOADED -> {
                CommandFeedback.sendError(source, "${screen.definition.name} is ready but has not started. Run /pm play to roll.")
                return 0
            }
            else -> {}
        }
        val playing = ScreenManager.togglePause(screen)
        val verb = if (playing) "Resumed" else "Paused"
        CommandFeedback.sendSuccess(source, "$verb ${screen.definition.name}.")
        return 1
    }

    fun stop(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, _) = targetAndRest(source, args) ?: return 0
        ScreenManager.stop(screen)
        CommandFeedback.sendSuccess(source, "Stopped playback on ${screen.definition.name}.")
        return 1
    }

    fun seek(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (screen.playback.state == PlayState.STOPPED) {
            CommandFeedback.sendError(source, "Nothing is playing on ${screen.definition.name}.")
            return 0
        }
        if (rest.isEmpty()) {
            CommandFeedback.sendError(source, "Choose a time: /pm seek [screen] <1:23:45, 5:30, +30, or -1:30>")
            return 0
        }
        val target = Times.parseMs(rest, screen.playback.currentPositionMs())
        if (target == null) {
            CommandFeedback.sendError(source, "Couldn't read '$rest'. Try 1:23:45, 5:30, 90, +30, or -1:30.")
            return 0
        }
        ScreenManager.seek(screen, target)
        CommandFeedback.sendSuccess(
            source,
            "Moved ${screen.playback.label} to ${Times.format(screen.playback.currentPositionMs())}.",
        )
        return 1
    }

    fun volume(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        val volume = rest.toIntOrNull()?.takeIf { it in 0..100 }
        if (volume == null) {
            CommandFeedback.sendError(source, "Volume must be from 0 to 100: /pm volume [screen] <0-100>")
            return 0
        }
        ScreenManager.setVolume(screen, volume / 100f)
        CommandFeedback.sendSuccess(source, "Set ${screen.definition.name} theater volume to $volume%.")
        return 1
    }

    /** Bare play: roll a loaded film (or resume a paused one). */
    private fun roll(source: CommandSourceStack, screen: ManagedScreen): Int {
        return when (screen.playback.state) {
            PlayState.LOADED, PlayState.PAUSED -> {
                ScreenManager.start(screen)
                CommandFeedback.sendSuccess(
                    source,
                    "Rolling ${screen.playback.label} on ${screen.definition.name}.",
                )
                1
            }
            PlayState.PLAYING -> {
                CommandFeedback.sendError(source, "${screen.definition.name} is already playing.")
                0
            }
            PlayState.STOPPED -> {
                CommandFeedback.sendError(source, "Nothing is loaded. Use /pm load <movie> or /pm play <movie>.")
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
                server.execute { CommandFeedback.sendError(source, e.message ?: "Could not resolve that movie.") }
                return@startVirtualThread
            }
            server.execute { onResolved(resolved) }
        }
    }
}
