package dev.zephbyte.premiere.command

import com.mojang.brigadier.context.CommandContext
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.upload.MovieLibrary
import dev.zephbyte.premiere.upload.R2Storage
import dev.zephbyte.premiere.upload.UploadServer
import dev.zephbyte.premiere.util.Times
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

/** Everything that isn't geometry or playback: dashboard, library, config. */
internal object AdminCommands {

    fun dashboard(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            CommandFeedback.sendError(source, "The dashboard needs the R2 settings in config/premiere.json.")
            return 0
        }
        val token = UploadServer.mintToken()
        if (token == null) {
            CommandFeedback.sendError(source, "Couldn't start the dashboard. Check the server log for the port error.")
            return 0
        }
        val base = UploadServer.dashboardBaseAddress()
        val url = "$base/dash?token=$token"
        source.sendSuccess({
            CommandFeedback.info("Staff dashboard  ")
                .append(CommandFeedback.url("[ OPEN DASHBOARD ]", url))
        }, false)
        CommandFeedback.sendInfo(source, "Private session link • expires in 1 hour.")
        if (!UploadServer.hasPublicAddress) {
            CommandFeedback.sendInfo(
                source,
                "This automatic link works on the server's local network. For remote staff, set upload_public_address to an HTTPS proxy or tunnel URL.",
            )
        }
        return 1
    }

    fun movies(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            CommandFeedback.sendError(source, "No movie library is configured. Add the R2 settings in config/premiere.json.")
            return 0
        }
        val server = source.server
        Thread.startVirtualThread {
            val names = try {
                MovieLibrary.displayNamesWithCc(R2Storage.listKeys())
            } catch (e: Exception) {
                server.execute {
                    CommandFeedback.sendError(source, "Couldn't reach the movie library: ${e.message}")
                }
                return@startVirtualThread
            }
            server.execute {
                if (names.isEmpty()) {
                    CommandFeedback.sendInfo(source, "The movie library is empty. Add a movie through /pm dashboard.")
                } else {
                    source.sendSuccess({ CommandFeedback.info("Movie library — ${names.size} titles") }, false)
                    names.forEach { displayName ->
                        val movie = displayName.removeSuffix(" (cc)")
                        source.sendSuccess({
                            Component.literal("  • ").withStyle(ChatFormatting.DARK_GRAY)
                                .append(CommandFeedback.command(displayName, "/pm load $movie"))
                        }, false)
                    }
                }
            }
        }
        return 1
    }

    fun reload(context: CommandContext<CommandSourceStack>): Int {
        if (!PremiereConfig.reload()) {
            CommandFeedback.sendError(context.source, "Config reload failed. Fix the server-log error; the current settings are still active.")
            return 0
        }
        // The dashboard restarts lazily so a port change takes effect; open
        // sessions lose their tokens, which is fine.
        UploadServer.stop()
        CommandFeedback.sendSuccess(context.source, "Config reloaded. New audio settings apply to the next movie.")
        context.source.sendSuccess({
            CommandFeedback.info("Dashboard sessions were refreshed. ")
                .append(CommandFeedback.command("Run /pm dashboard", "/pm dashboard"))
                .append(CommandFeedback.muted(" for a new link."))
        }, false)
        return 1
    }

    fun list(context: CommandContext<CommandSourceStack>): Int {
        val screens = ScreenManager.all()
        if (screens.isEmpty()) {
            CommandFeedback.sendInfo(context.source, "No screens are defined. Use /pm wand to create one.")
            return 0
        }
        context.source.sendSuccess({ CommandFeedback.info("Screens — ${screens.size} defined") }, false)
        for (screen in screens) {
            val d = screen.definition
            val p = screen.playback
            val (status, color) = when (p.state) {
                PlayState.STOPPED -> "IDLE" to ChatFormatting.DARK_GRAY
                PlayState.LOADED -> "READY" to ChatFormatting.AQUA
                PlayState.PAUSED -> "PAUSED" to ChatFormatting.YELLOW
                PlayState.PLAYING -> "PLAYING" to ChatFormatting.GREEN
            }
            context.source.sendSuccess({
                Component.literal("  ● ").withStyle(color)
                    .append(Component.literal(d.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                    .append(Component.literal("  $status").withStyle(color))
                    .append(Component.literal("  ${d.width}×${d.height} ${d.facing.serializedName}").withStyle(ChatFormatting.GRAY))
                    .append(
                        Component.literal(
                            "  full audio ${PlaybackCommands.blocks(screen.effectiveAudioFullVolumeRadius())}b" +
                                if (screen.audioFullVolumeRadiusOverride == null) " default" else " custom",
                        ).withStyle(ChatFormatting.DARK_GRAY),
                    )
                    .append(
                        if (screen.queue.isEmpty()) Component.empty()
                        else Component.literal("  ${screen.queue.size} queued").withStyle(ChatFormatting.AQUA),
                    )
                    .append(
                        if (p.state == PlayState.STOPPED) Component.empty()
                        else Component.literal("  ${Times.format(p.currentPositionMs())} • ${p.label}")
                            .withStyle(ChatFormatting.DARK_GRAY)
                    )
            }, false)
        }
        return screens.size
    }
}
