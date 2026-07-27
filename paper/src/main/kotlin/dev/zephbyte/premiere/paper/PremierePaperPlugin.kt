package dev.zephbyte.premiere.paper

import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.PremiereCore
import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.upload.UploadServer
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper entrypoint: the server half of Premiere as a Bukkit plugin, wire-
 * compatible with the same Fabric client mod that a Fabric server serves.
 * Deliberately Bukkit-API-only (no NMS): like the Fabric side, this registers
 * no blocks/items/entities, so vanilla clients are never affected.
 */
class PremierePaperPlugin : JavaPlugin() {

    override fun onEnable() {
        PremiereConfig.load(dataFolder.toPath())

        // Channels must be registered in onEnable so the server advertises
        // them (minecraft:register) to modded clients at join.
        PremiereMessenger.register(this)

        ScreenManager.start(PaperPlatform(this))

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            PaperCommands.register(this, event.registrar())
        }

        server.pluginManager.registerEvents(SelectionListener, this)

        val ticks = ScreenManager.REBROADCAST_TICKS.toLong()
        server.scheduler.runTaskTimer(this, Runnable { ScreenManager.rebroadcastPlaying() }, ticks, ticks)

        PremiereCore.LOGGER.info("Premiere (Paper) enabled — vanilla clients unaffected, modded clients get the show")
    }

    override fun onDisable() {
        UploadServer.stop()
        ScreenManager.stop()
    }
}
