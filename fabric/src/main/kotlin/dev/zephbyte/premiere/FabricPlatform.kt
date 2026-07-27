package dev.zephbyte.premiere

import dev.zephbyte.premiere.command.MoviePerms
import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.net.ScreenStatePayload
import dev.zephbyte.premiere.platform.PlayerHandle
import dev.zephbyte.premiere.platform.PremierePlatform
import dev.zephbyte.premiere.wire.ScreenStateMessage
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
import java.util.UUID

/** Fabric's implementation of the shared core's platform surface. */
class FabricPlatform(private val server: MinecraftServer) : PremierePlatform {

    override val screensFile: Path
        get() = server.getWorldPath(LevelResource.ROOT).resolve("premiere_screens.json")

    override fun runOnServerThread(task: () -> Unit) = server.execute(task)

    override fun onlinePlayers(): List<PlayerHandle> =
        PlayerLookup.all(server).map { FabricPlayerHandle(it) }

    override fun player(uuid: UUID): PlayerHandle? =
        server.playerList.getPlayer(uuid)?.let { FabricPlayerHandle(it) }
}

class FabricPlayerHandle(private val player: ServerPlayer) : PlayerHandle {
    override val uuid: UUID get() = player.uuid
    override val name: String get() = player.gameProfile.name

    override val dimension: String
        get() = player.level().dimension().identifier().toString()

    override val position: Vec3d get() = player.position().toVec3d()

    override val canReceiveScreenState: Boolean
        get() = ServerPlayNetworking.canSend(player, ScreenStatePayload.TYPE)

    override fun sendScreenState(msg: ScreenStateMessage) =
        ServerPlayNetworking.send(player, ScreenStatePayload(msg))

    override fun sendChat(text: String) = player.sendSystemMessage(Component.literal(text))

    override fun sendActionBar(text: String) = player.sendOverlayMessage(Component.literal(text))

    override fun hasControlPermission(): Boolean =
        MoviePerms.canControl(player.createCommandSourceStack())
}
