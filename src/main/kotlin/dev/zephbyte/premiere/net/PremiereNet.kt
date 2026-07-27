package dev.zephbyte.premiere.net

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Snapshot of one screen, sent to video-capable clients only (Fabric drops
 * custom payloads for clients that never declared the channel, so vanilla
 * clients receive nothing at all).
 *
 * [mediaPositionMs] is the master-clock media position at the moment the server
 * built this payload; clients anchor to their local clock on receipt, so
 * server/client wall-clock skew cancels out (modulo one-way latency, well under
 * the sync tolerance).
 */
data class ScreenStatePayload(
    val screen: ScreenDefinition,
    val url: String,
    val state: PlayState,
    val mediaPositionMs: Long,
    val volume: Float,
    val removed: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ScreenStatePayload> = TYPE

    private fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(screen.name)
        buf.writeUtf(screen.dimension)
        buf.writeBlockPos(screen.origin)
        buf.writeVarInt(screen.width)
        buf.writeVarInt(screen.height)
        buf.writeEnum(screen.facing)
        buf.writeUtf(url)
        buf.writeEnum(state)
        buf.writeVarLong(mediaPositionMs)
        buf.writeFloat(volume)
        buf.writeBoolean(removed)
    }

    companion object {
        val TYPE = CustomPacketPayload.Type<ScreenStatePayload>(Premiere.id("screen_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ScreenStatePayload> =
            CustomPacketPayload.codec(ScreenStatePayload::write) { buf ->
                ScreenStatePayload(
                    screen = ScreenDefinition(
                        name = buf.readUtf(),
                        dimension = buf.readUtf(),
                        origin = buf.readBlockPos(),
                        width = buf.readVarInt(),
                        height = buf.readVarInt(),
                        facing = buf.readEnum(Direction::class.java),
                    ),
                    url = buf.readUtf(),
                    state = buf.readEnum(PlayState::class.java),
                    mediaPositionMs = buf.readVarLong(),
                    volume = buf.readFloat(),
                    removed = buf.readBoolean(),
                )
            }
    }
}

/**
 * Sent by a modded client after joining to request the current state of every
 * screen. This is what makes late joiners (and mid-film installs) work; the
 * server must never rely on only pushing updates going forward.
 */
class RequestScreensPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RequestScreensPayload> = TYPE

    companion object {
        val INSTANCE = RequestScreensPayload()
        val TYPE = CustomPacketPayload.Type<RequestScreensPayload>(Premiere.id("request_screens"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestScreensPayload> =
            StreamCodec.unit(INSTANCE)
    }
}

object PremiereNet {
    fun registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(ScreenStatePayload.TYPE, ScreenStatePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RequestScreensPayload.TYPE, RequestScreensPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestScreensPayload.TYPE) { _, context ->
            dev.zephbyte.premiere.screen.ScreenManager.sendAllTo(context.player())
        }
    }
}
