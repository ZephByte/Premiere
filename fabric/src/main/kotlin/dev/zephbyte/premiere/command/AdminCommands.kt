package dev.zephbyte.premiere.command

import com.mojang.brigadier.context.CommandContext
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.upload.MovieLibrary
import dev.zephbyte.premiere.upload.R2Storage
import dev.zephbyte.premiere.upload.UploadServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import java.net.URI

/** Everything that isn't geometry or playback: dashboard, library, config. */
internal object AdminCommands {

    fun upload(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            source.sendFailure(
                Component.literal("Uploads aren't configured. Fill in the r2_* settings in config/premiere.json (see README).")
            )
            return 0
        }
        val token = UploadServer.mintToken()
        if (token == null) {
            source.sendFailure(Component.literal("Could not start the dashboard; check the server log."))
            return 0
        }
        val base = PremiereConfig.uploadPublicAddress.ifBlank {
            "http://<this-server's-address>:${PremiereConfig.uploadHttpPort}"
        }
        val url = "$base/dash?token=$token"
        val link = Component.literal(url).withStyle { style ->
            runCatching { style.withClickEvent(ClickEvent.OpenUrl(URI(url))) }
                .getOrDefault(style)
                .withUnderlined(true)
        }
        source.sendSuccess({ Component.literal("Dashboard link (valid 1 hour): ").append(link) }, false)
        if (PremiereConfig.uploadPublicAddress.isBlank()) {
            source.sendSuccess({
                Component.literal("Tip: set upload_public_address in config/premiere.json to make this link clickable.")
            }, false)
        }
        return 1
    }

    fun movies(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!PremiereConfig.uploadConfigured) {
            source.sendFailure(Component.literal("No movie library configured (r2_* settings in config/premiere.json)."))
            return 0
        }
        val server = source.server
        Thread.startVirtualThread {
            val names = try {
                MovieLibrary.displayNamesWithCc(R2Storage.listKeys())
            } catch (e: Exception) {
                server.execute {
                    source.sendFailure(Component.literal("Could not reach the movie library: ${e.message}"))
                }
                return@startVirtualThread
            }
            server.execute {
                if (names.isEmpty()) {
                    source.sendSuccess({ Component.literal("The library is empty. Add movies with /pm upload.") }, false)
                } else {
                    source.sendSuccess({ Component.literal("Movies: ${names.joinToString(", ")}") }, false)
                }
            }
        }
        return 1
    }

    fun reload(context: CommandContext<CommandSourceStack>): Int {
        PremiereConfig.reload()
        // The dashboard restarts lazily so a port change takes effect; open
        // sessions lose their tokens, which is fine.
        UploadServer.stop()
        context.source.sendSuccess({
            Component.literal(
                "Premiere config reloaded. Audio settings apply from the next play; run /pm upload for a fresh dashboard."
            )
        }, true)
        return 1
    }

    fun list(context: CommandContext<CommandSourceStack>): Int {
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
