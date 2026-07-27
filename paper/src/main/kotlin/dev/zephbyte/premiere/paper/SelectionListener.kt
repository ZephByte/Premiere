package dev.zephbyte.premiere.paper

import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.screen.SelectionTool
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin

/** Paper event glue for the platform-free [SelectionTool]. */
object SelectionListener : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val first = when (event.action) {
            Action.LEFT_CLICK_BLOCK -> true
            Action.RIGHT_CLICK_BLOCK -> false
            else -> return
        }
        val block = event.clickedBlock ?: return
        val plugin = JavaPlugin.getPlugin(PremierePaperPlugin::class.java)
        val consumed = SelectionTool.handleClick(
            PaperPlayerHandle(plugin, event.player),
            event.player.world.key.toString(),
            ScreenPos(block.x, block.y, block.z),
            first,
        )
        // Cancel so nothing breaks or places — same as Fabric's FAIL result.
        if (consumed) event.isCancelled = true
    }
}
