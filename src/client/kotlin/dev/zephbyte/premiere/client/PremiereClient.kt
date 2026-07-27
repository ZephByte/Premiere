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
    }
}
