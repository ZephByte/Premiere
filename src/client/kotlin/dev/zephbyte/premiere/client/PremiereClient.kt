package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.client.video.ScreenRenderer
import dev.zephbyte.premiere.net.RequestScreensPayload
import dev.zephbyte.premiere.net.ScreenStatePayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * The optional video client. Everything here is additive: a player without this
 * mod connects to the same server normally and simply sees the blank wall.
 */
class PremiereClient : ClientModInitializer {

    override fun onInitializeClient() {
        PremiereClientConfig.load()
        ClientPlayNetworking.registerGlobalReceiver(ScreenStatePayload.TYPE) { payload, _ ->
            ClientScreens.handle(payload)
        }
        // Ask for the full screen state on join so late joiners (or a mid-film
        // mod install) sync to the current film position instead of waiting for
        // the next push.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (ClientPlayNetworking.canSend(RequestScreensPayload.TYPE)) {
                ClientPlayNetworking.send(RequestScreensPayload.INSTANCE)
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ClientScreens.clear()
        }
        ScreenRenderer.register()
        dev.zephbyte.premiere.client.subtitles.SubtitleHud.register()
        registerSubtitleKeybind()
    }

    private fun registerSubtitleKeybind() {
        val key = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
            net.minecraft.client.KeyMapping(
                "key.premiere.subtitles",
                org.lwjgl.glfw.GLFW.GLFW_KEY_K,
                net.minecraft.client.KeyMapping.Category.MISC,
            )
        )
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (key.consumeClick()) {
                val enabled = PremiereClientConfig.toggleSubtitles()
                client.player?.sendOverlayMessage(
                    net.minecraft.network.chat.Component.literal(
                        if (enabled) "Movie subtitles on" else "Movie subtitles off"
                    )
                )
            }
        }
    }
}
