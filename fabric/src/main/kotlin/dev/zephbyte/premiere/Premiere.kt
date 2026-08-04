package dev.zephbyte.premiere

import dev.zephbyte.premiere.command.PremiereCommand
import dev.zephbyte.premiere.net.PremiereNet
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.screen.SelectionToolEvents
import dev.zephbyte.premiere.upload.UploadServer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier

/**
 * Server/common entrypoint.
 *
 * Deliberately registers no Block, Item, Fluid, or BlockEntityType: registry
 * additions are what make fabric-registry-sync-v0 kick vanilla clients at login,
 * and letting fully vanilla clients connect is this mod's core constraint.
 * Everything here is commands, plain data, and custom payloads (which Fabric
 * only sends to clients that declared the channel).
 */
class Premiere : ModInitializer {

    override fun onInitialize() {
        PremiereConfig.load(FabricLoader.getInstance().configDir)
        PremiereNet.registerCommon()
        PremiereCommand.register()
        SelectionToolEvents.register()
        registerLifecycle()
        LOGGER.info("Premiere initialized (no registry entries, vanilla clients unaffected)")
    }

    private fun registerLifecycle() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ScreenManager.start(FabricPlatform(server))
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            UploadServer.stop()
            ScreenManager.stop()
        }
        var tickCounter = 0
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            ScreenManager.tick()
            if (++tickCounter >= ScreenManager.REBROADCAST_TICKS) {
                tickCounter = 0
                ScreenManager.rebroadcastPlaying()
            }
        }
    }

    companion object {
        // Aliases to the shared core so existing call sites don't churn.
        const val MOD_ID = PremiereCore.MOD_ID
        val LOGGER = PremiereCore.LOGGER

        fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
    }
}
