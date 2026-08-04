package dev.zephbyte.premiere.command

import com.mojang.brigadier.context.CommandContext
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.command.PremiereCommand.targetAndRest
import dev.zephbyte.premiere.screen.ManagedScreen
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.QueuedMedia
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.upload.MediaResolver
import dev.zephbyte.premiere.command.PlayArgs.parseAudioFlag
import dev.zephbyte.premiere.util.Times
import net.minecraft.commands.CommandSourceStack
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Playback control. Every command here accepts an optional leading screen
 * name (see [PremiereCommand.targetAndRest]).
 */
internal object PlaybackCommands {


    fun play(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isNotEmpty()) {
            CommandFeedback.sendError(
                source,
                "Movies must be prepared first. Use /pm load [screen] <movie or URL>, then /pm play when it is ready.",
            )
            return 0
        }
        return roll(source, screen)
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

    fun queue(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            if (screen.queue.isEmpty()) {
                CommandFeedback.sendInfo(source, "${screen.definition.name}'s queue is empty.")
                return 1
            }
            source.sendSuccess({ CommandFeedback.info("Up next on ${screen.definition.name} — ${screen.queue.size} queued") }, false)
            screen.queue.forEachIndexed { index, media ->
                source.sendSuccess({
                    Component.literal("  ${index + 1}. ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(media.label).withStyle(ChatFormatting.WHITE))
                        .append(CommandFeedback.muted("  "))
                        .append(
                            CommandFeedback.command(
                                "[remove]",
                                "/pm queue ${screen.definition.name} remove ${index + 1}",
                            ),
                        )
                }, false)
            }
            return screen.queue.size
        }
        if (rest.equals("clear", ignoreCase = true)) {
            val count = ScreenManager.clearQueue(screen)
            CommandFeedback.sendSuccess(source, "Cleared $count queued ${if (count == 1) "movie" else "movies"} from ${screen.definition.name}.")
            return 1
        }
        if (rest.startsWith("remove ", ignoreCase = true)) {
            val index = rest.substringAfter(' ').trim().toIntOrNull()
            val removed = index?.let { ScreenManager.removeQueued(screen, it - 1) }
            if (removed == null) {
                CommandFeedback.sendError(source, "Choose a queue number from 1 to ${screen.queue.size}.")
                return 0
            }
            CommandFeedback.sendSuccess(source, "Removed ${removed.label} from ${screen.definition.name}'s queue.")
            return 1
        }
        val (input, audioLanguage) = parseAudioFlag(rest)
        resolveMedia(source, input) { resolved ->
            val media = QueuedMedia(resolved.url, resolved.label, resolved.subtitleUrl, audioLanguage)
            val position = ScreenManager.enqueue(screen, media)
            if (screen.playback.state == PlayState.STOPPED) {
                ScreenManager.loadNext(screen, source.player?.uuid)
                CommandFeedback.sendSuccess(
                    source,
                    "Preparing ${screen.playback.label} on ${screen.definition.name}; it will roll when viewers are ready and ${screen.queue.size} remain queued.",
                )
            } else {
                CommandFeedback.sendSuccess(source, "Queued ${resolved.label} as #$position on ${screen.definition.name}.")
            }
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

    fun radius(context: CommandContext<CommandSourceStack>, args: String): Int {
        val source = context.source
        val (screen, rest) = targetAndRest(source, args) ?: return 0
        if (rest.isEmpty()) {
            val origin = if (screen.audioFullVolumeRadiusOverride == null) "config default" else "screen override"
            CommandFeedback.sendInfo(
                source,
                "${screen.definition.name} has a ${blocks(screen.effectiveAudioFullVolumeRadius())}-block full-volume audience radius ($origin).",
            )
            return 1
        }
        if (rest.equals("default", ignoreCase = true)) {
            if (!ScreenManager.setAudioFullVolumeRadius(screen, null)) {
                CommandFeedback.sendError(source, "Couldn't save ${screen.definition.name}; its audience radius is unchanged.")
                return 0
            }
            CommandFeedback.sendSuccess(
                source,
                "${screen.definition.name} now follows the ${blocks(PremiereConfig.audioFullVolumeRadius)}-block config default.",
            )
            return 1
        }
        val radius = rest.toFloatOrNull()?.takeIf {
            it.isFinite() && it >= 0f && it < PremiereConfig.audioDistance
        }
        if (radius == null) {
            CommandFeedback.sendError(
                source,
                "Radius must be at least 0 and below ${blocks(PremiereConfig.audioDistance)} blocks, or 'default'.",
            )
            return 0
        }
        if (!ScreenManager.setAudioFullVolumeRadius(screen, radius)) {
            CommandFeedback.sendError(source, "Couldn't save ${screen.definition.name}; its audience radius is unchanged.")
            return 0
        }
        CommandFeedback.sendSuccess(
            source,
            "Set ${screen.definition.name}'s full-volume audience radius to ${blocks(radius)} blocks.",
        )
        return 1
    }

    fun blocks(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

    /** Bare play: roll a loaded film (or resume a paused one). */
    private fun roll(source: CommandSourceStack, screen: ManagedScreen): Int {
        return when (screen.playback.state) {
            PlayState.LOADED -> {
                if (!ScreenManager.start(screen)) {
                    CommandFeedback.sendError(source, "${screen.playback.label} is still buffering on viewers' clients.")
                    return 0
                }
                CommandFeedback.sendSuccess(
                    source,
                    "Rolling ${screen.playback.label} on ${screen.definition.name}.",
                )
                1
            }
            PlayState.PAUSED -> {
                ScreenManager.start(screen)
                CommandFeedback.sendSuccess(source, "Resumed ${screen.playback.label} on ${screen.definition.name}.")
                1
            }
            PlayState.PLAYING -> {
                CommandFeedback.sendError(source, "${screen.definition.name} is already playing.")
                0
            }
            PlayState.STOPPED -> {
                CommandFeedback.sendError(source, "Nothing is loaded. Use /pm load [screen] <movie or URL> first.")
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
