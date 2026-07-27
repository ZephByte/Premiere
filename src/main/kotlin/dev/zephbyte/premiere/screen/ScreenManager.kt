package dev.zephbyte.premiere.screen

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.audio.AudioBridge
import dev.zephbyte.premiere.net.ScreenStatePayload
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path

class ManagedScreen(val definition: ScreenDefinition) {
    val playback = Playback()
}

/**
 * Server-side registry of screens. Geometry is persisted as plain JSON in the
 * world folder, deliberately independent of the physical wall blocks (which can
 * be griefed or world-edited away without corrupting anything). Playback state
 * is not persisted; a restart mid-film means staff replays.
 */
object ScreenManager {
    private val screens = LinkedHashMap<String, ManagedScreen>()
    private var server: MinecraftServer? = null

    /** Rebroadcast cadence for playing screens; doubles as drift correction. */
    private const val REBROADCAST_TICKS = 200
    private var tickCounter = 0

    fun init() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server
            load(server)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            AudioBridge.instance?.shutdownAll()
            server = null
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++tickCounter >= REBROADCAST_TICKS) {
                tickCounter = 0
                screens.values.filter { it.playback.state == PlayState.PLAYING }
                    .forEach { broadcast(server, it) }
            }
        }
    }

    fun all(): Collection<ManagedScreen> = screens.values

    fun get(name: String): ManagedScreen? = screens[name]

    fun define(server: MinecraftServer, definition: ScreenDefinition): Boolean {
        if (screens.containsKey(definition.name)) return false
        val screen = ManagedScreen(definition)
        screens[definition.name] = screen
        save(server)
        broadcast(server, screen)
        return true
    }

    fun undefine(server: MinecraftServer, name: String): Boolean {
        val screen = screens.remove(name) ?: return false
        screen.playback.stop()
        AudioBridge.instance?.onScreenRemoved(name)
        save(server)
        sendToAll(server, payloadFor(screen, removed = true))
        return true
    }

    fun play(server: MinecraftServer, screen: ManagedScreen, url: String) {
        screen.playback.play(url)
        pushPlayback(server, screen)
    }

    /** Returns true if now playing, false if now paused. */
    fun togglePause(server: MinecraftServer, screen: ManagedScreen): Boolean {
        val playback = screen.playback
        if (playback.state == PlayState.PAUSED) playback.resume() else playback.pause()
        pushPlayback(server, screen)
        return playback.state == PlayState.PLAYING
    }

    fun stop(server: MinecraftServer, screen: ManagedScreen) {
        screen.playback.stop()
        pushPlayback(server, screen)
    }

    fun setVolume(server: MinecraftServer, screen: ManagedScreen, volume: Float) {
        screen.playback.volume = volume
        pushPlayback(server, screen)
    }

    private fun pushPlayback(server: MinecraftServer, screen: ManagedScreen) {
        broadcast(server, screen)
        AudioBridge.instance?.onPlaybackChanged(server, screen.definition, screen.playback)
    }

    /** Late joiner / channel registration: send the full current state. */
    fun sendAllTo(player: ServerPlayer) {
        screens.values.forEach { ServerPlayNetworking.send(player, payloadFor(it)) }
    }

    private fun broadcast(server: MinecraftServer, screen: ManagedScreen) {
        sendToAll(server, payloadFor(screen))
    }

    private fun sendToAll(server: MinecraftServer, payload: ScreenStatePayload) {
        for (player in PlayerLookup.all(server)) {
            // Fabric only delivers to clients that declared the channel, but
            // skip the send entirely for everyone else.
            if (ServerPlayNetworking.canSend(player, ScreenStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    private fun payloadFor(screen: ManagedScreen, removed: Boolean = false): ScreenStatePayload {
        val playback = screen.playback
        return ScreenStatePayload(
            screen = screen.definition,
            url = playback.url,
            state = playback.state,
            mediaPositionMs = playback.currentPositionMs(),
            volume = playback.volume,
            removed = removed,
        )
    }

    // --- persistence ---

    private fun file(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("premiere_screens.json")

    private fun load(server: MinecraftServer) {
        screens.clear()
        val path = file(server)
        if (!Files.exists(path)) return
        try {
            val root = JsonParser.parseString(Files.readString(path)).asJsonObject
            for (element in root.getAsJsonArray("screens")) {
                val o = element.asJsonObject
                val definition = ScreenDefinition(
                    name = o["name"].asString,
                    dimension = o["dimension"].asString,
                    origin = BlockPos(o["x"].asInt, o["y"].asInt, o["z"].asInt),
                    width = o["width"].asInt,
                    height = o["height"].asInt,
                    facing = Direction.byName(o["facing"].asString) ?: Direction.NORTH,
                )
                screens[definition.name] = ManagedScreen(definition)
            }
            Premiere.LOGGER.info("Loaded {} screen(s)", screens.size)
        } catch (e: Exception) {
            Premiere.LOGGER.error("Failed to load {}; starting with no screens", path, e)
        }
    }

    private fun save(server: MinecraftServer) {
        val array = JsonArray()
        for (screen in screens.values) {
            val d = screen.definition
            array.add(JsonObject().apply {
                addProperty("name", d.name)
                addProperty("dimension", d.dimension)
                addProperty("x", d.origin.x)
                addProperty("y", d.origin.y)
                addProperty("z", d.origin.z)
                addProperty("width", d.width)
                addProperty("height", d.height)
                addProperty("facing", d.facing.serializedName)
            })
        }
        val root = JsonObject().apply { add("screens", array) }
        try {
            Files.writeString(file(server), GsonBuilder().setPrettyPrinting().create().toJson(root))
        } catch (e: Exception) {
            Premiere.LOGGER.error("Failed to save screens", e)
        }
    }
}
