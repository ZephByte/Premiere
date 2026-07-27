package dev.zephbyte.premiere

import dev.zephbyte.premiere.command.MovieNightCommand
import dev.zephbyte.premiere.net.PremiereNet
import dev.zephbyte.premiere.screen.ScreenManager
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

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
        PremiereConfig.load()
        PremiereNet.registerCommon()
        MovieNightCommand.register()
        ScreenManager.init()
        dev.zephbyte.premiere.screen.SelectionTool.register()
        LOGGER.info("Premiere initialized (no registry entries, vanilla clients unaffected)")
    }

    companion object {
        const val MOD_ID = "premiere"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)!!

        fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
    }
}
