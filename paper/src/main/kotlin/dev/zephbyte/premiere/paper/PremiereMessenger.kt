package dev.zephbyte.premiere.paper

import dev.zephbyte.premiere.PremiereCore
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.wire.PremiereWire
import io.netty.buffer.Unpooled

/**
 * Plugin-messaging bindings for the shared wire protocol — the Paper
 * counterpart of the Fabric payload registry. Same channel IDs, same bytes
 * (all layout lives in [PremiereWire]); a Fabric client cannot tell this
 * server from a Fabric one.
 */
object PremiereMessenger {

    fun register(plugin: PremierePaperPlugin) {
        val messenger = plugin.server.messenger
        messenger.registerOutgoingPluginChannel(plugin, PremiereWire.SCREEN_STATE)

        messenger.registerIncomingPluginChannel(plugin, PremiereWire.REQUEST_SCREENS) { _, player, _ ->
            ScreenManager.sendAllTo(player.uniqueId)
        }
        messenger.registerIncomingPluginChannel(plugin, PremiereWire.SCREEN_READY) { _, player, bytes ->
            try {
                val msg = PremiereWire.readScreenReady(Unpooled.wrappedBuffer(bytes))
                ScreenManager.clientReportedReady(msg.screen, player.uniqueId)
            } catch (e: Exception) {
                PremiereCore.LOGGER.warn("Bad screen_ready payload from {}: {}", player.name, e.message)
            }
        }
    }
}
