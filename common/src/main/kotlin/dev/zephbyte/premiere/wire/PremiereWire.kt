package dev.zephbyte.premiere.wire

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import io.netty.buffer.ByteBuf

/**
 * The wire protocol, defined once for both server platforms and the client.
 * Channel IDs and byte layout are the compatibility contract: a Fabric client
 * must not be able to tell a Paper server from a Fabric one.
 *
 * Client mod and server artifact ship together from this repo, so the format
 * may change freely between releases — [WIRE_VERSION] exists so a stale client
 * fails loudly instead of decoding garbage.
 */
object PremiereWire {
    const val SCREEN_STATE = "premiere:screen_state"
    const val REQUEST_SCREENS = "premiere:request_screens"
    const val SCREEN_READY = "premiere:screen_ready"

    const val WIRE_VERSION = 1

    fun writeScreenState(buf: ByteBuf, msg: ScreenStateMessage) {
        Bufs.writeVarInt(buf, WIRE_VERSION)
        writeScreenDefinition(buf, msg.screen)
        Bufs.writeUtf(buf, msg.url)
        Bufs.writeUtf(buf, msg.subtitleUrl)
        Bufs.writeUtf(buf, msg.audioLanguage)
        buf.writeFloat(msg.audioDistance)
        Bufs.writeVarInt(buf, msg.state.ordinal)
        Bufs.writeVarLong(buf, msg.mediaPositionMs)
        buf.writeFloat(msg.volume)
        buf.writeBoolean(msg.removed)
    }

    fun readScreenState(buf: ByteBuf): ScreenStateMessage {
        val version = Bufs.readVarInt(buf)
        require(version == WIRE_VERSION) {
            "Premiere wire version mismatch: server sent $version, this client speaks $WIRE_VERSION — update the outdated side"
        }
        return ScreenStateMessage(
            screen = readScreenDefinition(buf),
            url = Bufs.readUtf(buf),
            subtitleUrl = Bufs.readUtf(buf),
            audioLanguage = Bufs.readUtf(buf),
            audioDistance = buf.readFloat(),
            state = PlayState.entries[Bufs.readVarInt(buf)],
            mediaPositionMs = Bufs.readVarLong(buf),
            volume = buf.readFloat(),
            removed = buf.readBoolean(),
        )
    }

    fun writeScreenReady(buf: ByteBuf, msg: ScreenReadyMessage) {
        Bufs.writeUtf(buf, msg.screen)
    }

    fun readScreenReady(buf: ByteBuf): ScreenReadyMessage = ScreenReadyMessage(Bufs.readUtf(buf))

    // RequestScreens carries no payload; nothing to read or write.

    private fun writeScreenDefinition(buf: ByteBuf, screen: ScreenDefinition) {
        Bufs.writeUtf(buf, screen.name)
        Bufs.writeUtf(buf, screen.dimension)
        Bufs.writeVarInt(buf, screen.origin.x)
        Bufs.writeVarInt(buf, screen.origin.y)
        Bufs.writeVarInt(buf, screen.origin.z)
        Bufs.writeVarInt(buf, screen.width)
        Bufs.writeVarInt(buf, screen.height)
        Bufs.writeVarInt(buf, screen.facing.ordinal)
    }

    private fun readScreenDefinition(buf: ByteBuf): ScreenDefinition = ScreenDefinition(
        name = Bufs.readUtf(buf),
        dimension = Bufs.readUtf(buf),
        origin = ScreenPos(Bufs.readVarInt(buf), Bufs.readVarInt(buf), Bufs.readVarInt(buf)),
        width = Bufs.readVarInt(buf),
        height = Bufs.readVarInt(buf),
        facing = ScreenFacing.entries[Bufs.readVarInt(buf)],
    )
}
