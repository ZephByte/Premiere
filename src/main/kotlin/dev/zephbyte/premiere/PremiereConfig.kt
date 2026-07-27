package dev.zephbyte.premiere

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Server-side knobs, in config/premiere.json. Written with defaults on first
 * run so operators can discover them.
 */
object PremiereConfig {

    /**
     * How far ahead of the master clock the audio track is fed into Simple
     * Voice Chat, compensating for its encode/transport/jitter-buffer latency.
     * If the sound arrives after the picture, increase; if before, decrease.
     */
    var audioLeadMs: Long = 150
        private set

    /** Audible radius (blocks) of a screen's audio channel. */
    var audioDistance: Float = 48f
        private set

    fun load() {
        val path = FabricLoader.getInstance().configDir.resolve("premiere.json")
        try {
            if (Files.exists(path)) {
                val o = JsonParser.parseString(Files.readString(path)).asJsonObject
                o["audio_lead_ms"]?.let { audioLeadMs = it.asLong }
                o["audio_distance"]?.let { audioDistance = it.asFloat }
            } else {
                Files.createDirectories(path.parent)
                val gson = GsonBuilder().setPrettyPrinting().create()
                Files.writeString(
                    path,
                    gson.toJson(
                        com.google.gson.JsonObject().apply {
                            addProperty("audio_lead_ms", audioLeadMs)
                            addProperty("audio_distance", audioDistance)
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Premiere.LOGGER.warn("Could not read {}; using defaults", path, e)
        }
    }
}
