package dev.zephbyte.premiere.paper

import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.platform.PlayerHandle
import dev.zephbyte.premiere.platform.PremierePlatform
import dev.zephbyte.premiere.wire.PremiereWire
import dev.zephbyte.premiere.wire.ScreenStateMessage
import io.netty.buffer.Unpooled
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.nio.file.Path
import java.util.UUID

/** Paper's implementation of the shared core's platform surface. */
class PaperPlatform(private val plugin: PremierePaperPlugin) : PremierePlatform {

    override val screensFile: Path
        // Save root (worldContainer/levelName), matching Fabric's
        // LevelResource.ROOT — so a world migrated between Fabric and Paper
        // keeps its screens. Not World.worldFolder: on current Paper that
        // points into dimensions/minecraft/overworld/.
        get() = Bukkit.getWorldContainer().toPath()
            .resolve(Bukkit.getWorlds().first().name)
            .resolve("premiere_screens.json")

    override fun runOnServerThread(task: () -> Unit) {
        if (Bukkit.isPrimaryThread()) task()
        else Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun onlinePlayers(): List<PlayerHandle> =
        Bukkit.getOnlinePlayers().map { PaperPlayerHandle(plugin, it) }

    override fun player(uuid: UUID): PlayerHandle? =
        Bukkit.getPlayer(uuid)?.let { PaperPlayerHandle(plugin, it) }
}

class PaperPlayerHandle(
    private val plugin: PremierePaperPlugin,
    private val player: Player,
) : PlayerHandle {
    override val uuid: UUID get() = player.uniqueId
    override val name: String get() = player.name

    override val dimension: String get() = player.world.key.toString()

    override val position: Vec3d
        get() = player.location.let { Vec3d(it.x, it.y, it.z) }

    /**
     * Bukkit tracks the channels each client announced via minecraft:register
     * — the same signal Fabric's ServerPlayNetworking.canSend uses, so the
     * "modded clients only" gate behaves identically on both platforms.
     */
    override val canReceiveScreenState: Boolean
        get() = PremiereWire.SCREEN_STATE in player.listeningPluginChannels

    override fun sendScreenState(msg: ScreenStateMessage) {
        val buf = Unpooled.buffer()
        PremiereWire.writeScreenState(buf, msg)
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        player.sendPluginMessage(plugin, PremiereWire.SCREEN_STATE, bytes)
    }

    override fun sendChat(text: String) = player.sendMessage(Component.text(text))

    override fun sendLoadReady(mediaLabel: String, reporterName: String, screenName: String) {
        val playCommand = "/pm play $screenName"
        player.sendMessage(
            Component.text("Premiere ", NamedTextColor.DARK_PURPLE)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("✓ ", NamedTextColor.GREEN))
                .append(Component.text("'$mediaLabel' is ready on $screenName. ", NamedTextColor.GRAY))
                .append(Component.text("Buffered by $reporterName. ", NamedTextColor.DARK_GRAY))
                .append(
                    Component.text("Run $playCommand", NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.suggestCommand(playCommand))
                )
        )
    }

    override fun sendActionBar(text: String) = player.sendActionBar(Component.text(text))

    override fun hasControlPermission(): Boolean = PaperCommands.hasControlPermission(player)
}
