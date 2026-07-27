package dev.zephbyte.premiere.net

import dev.zephbyte.premiere.screen.ScreenManager
import dev.zephbyte.premiere.wire.PremiereWire
import dev.zephbyte.premiere.wire.ScreenReadyMessage
import dev.zephbyte.premiere.wire.ScreenStateMessage
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Fabric bindings for the shared wire protocol. All byte layout lives in
 * [PremiereWire] (:common) so the Paper plugin stays wire-identical; these
 * wrappers only adapt it to Fabric's payload registry. FriendlyByteBuf
 * extends netty ByteBuf, so the common codecs write into it directly.
 */

class ScreenStatePayload(val msg: ScreenStateMessage) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ScreenStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ScreenStatePayload>(Identifier.parse(PremiereWire.SCREEN_STATE))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ScreenStatePayload> =
            CustomPacketPayload.codec(
                { payload, buf -> PremiereWire.writeScreenState(buf, payload.msg) },
                { buf -> ScreenStatePayload(PremiereWire.readScreenState(buf)) },
            )
    }
}

class RequestScreensPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RequestScreensPayload> = TYPE

    companion object {
        val INSTANCE = RequestScreensPayload()
        val TYPE = CustomPacketPayload.Type<RequestScreensPayload>(Identifier.parse(PremiereWire.REQUEST_SCREENS))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestScreensPayload> =
            StreamCodec.unit(INSTANCE)
    }
}

class ScreenReadyPayload(val msg: ScreenReadyMessage) : CustomPacketPayload {
    constructor(screen: String) : this(ScreenReadyMessage(screen))

    val screen: String get() = msg.screen

    override fun type(): CustomPacketPayload.Type<ScreenReadyPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ScreenReadyPayload>(Identifier.parse(PremiereWire.SCREEN_READY))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ScreenReadyPayload> =
            CustomPacketPayload.codec(
                { payload, buf -> PremiereWire.writeScreenReady(buf, payload.msg) },
                { buf -> ScreenReadyPayload(PremiereWire.readScreenReady(buf)) },
            )
    }
}

object PremiereNet {
    fun registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(ScreenStatePayload.TYPE, ScreenStatePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RequestScreensPayload.TYPE, RequestScreensPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ScreenReadyPayload.TYPE, ScreenReadyPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RequestScreensPayload.TYPE) { _, context ->
            ScreenManager.sendAllTo(context.player().uuid)
        }
        ServerPlayNetworking.registerGlobalReceiver(ScreenReadyPayload.TYPE) { payload, context ->
            ScreenManager.clientReportedReady(payload.screen, context.player().uuid)
        }
    }
}
