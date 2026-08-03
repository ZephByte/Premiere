package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.client.video.ScreenRenderer
import dev.zephbyte.premiere.net.RequestScreensPayload
import dev.zephbyte.premiere.net.ScreenStatePayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import dev.zephbyte.premiere.client.subtitles.SubtitleHud
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

/**
 * The optional video client. Everything here is additive: a player without this
 * mod connects to the same server normally and simply sees the blank wall.
 */
class PremiereClient : ClientModInitializer {

    private var initialScreenRequestPending = false

    override fun onInitializeClient() {
        PremiereClientConfig.load()
        ClientPlayNetworking.registerGlobalReceiver(ScreenStatePayload.TYPE) { payload, _ ->
            ClientScreens.handle(payload)
        }
        // Ask for the full screen state on join so late joiners (or a mid-film
        // mod install) sync to the current film position instead of waiting for
        // the next push.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            // Paper can advertise its plugin channels a few ticks after this
            // callback. Keep the request pending instead of silently giving
            // up and waiting for the ten-second playback heartbeat.
            initialScreenRequestPending = true
            requestInitialScreensIfAvailable()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            initialScreenRequestPending = false
            ClientScreens.clear()
        }
        ScreenRenderer.register()
        SubtitleHud.register()
        registerSubtitleKeybind()
    }

    private fun registerSubtitleKeybind() {
        // Own section in the Controls screen, like SVC has.
        val category = KeyMapping.Category.register(dev.zephbyte.premiere.Premiere.id("main"))
        val subtitleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.premiere.subtitles", GLFW.GLFW_KEY_K, category)
        )
        val settingsKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.premiere.settings", GLFW.GLFW_KEY_COMMA, category)
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            requestInitialScreensIfAvailable()
            ClientScreens.tick()
            while (subtitleKey.consumeClick()) {
                val enabled = PremiereClientConfig.toggleSubtitles()
                client.player?.sendOverlayMessage(
                    Component.literal(
                        if (enabled) "Movie subtitles on" else "Movie subtitles off"
                    )
                )
            }
            while (settingsKey.consumeClick()) {
                client.gui.setScreen(PremiereSettingsScreen(client.gui.screen()))
            }
        }
    }

    private fun requestInitialScreensIfAvailable() {
        if (!initialScreenRequestPending || !ClientPlayNetworking.canSend(RequestScreensPayload.TYPE)) return
        ClientPlayNetworking.send(RequestScreensPayload.INSTANCE)
        initialScreenRequestPending = false
    }
}
