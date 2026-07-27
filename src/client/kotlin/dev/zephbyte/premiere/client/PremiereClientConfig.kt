package dev.zephbyte.premiere.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.zephbyte.premiere.Premiere
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/** Client-side preferences, in config/premiere-client.json. */
object PremiereClientConfig {

    @Volatile
    var subtitlesEnabled: Boolean = false
        private set

    /**
     * Preferred language for subtitle tracks embedded in the film, as an
     * ISO 639 code the way releases tag them ("eng", "jpn", "spa", ...).
     */
    @Volatile
    var subtitleLanguage: String = "eng"
        private set

    fun toggleSubtitles(): Boolean {
        subtitlesEnabled = !subtitlesEnabled
        save()
        return subtitlesEnabled
    }

    fun load() {
        val path = FabricLoader.getInstance().configDir.resolve("premiere-client.json")
        try {
            if (Files.exists(path)) {
                val o = JsonParser.parseString(Files.readString(path)).asJsonObject
                o["subtitles"]?.let { subtitlesEnabled = it.asBoolean }
                o["subtitle_language"]?.let { subtitleLanguage = it.asString.lowercase() }
                save() // keep the file discoverable as new keys appear
            } else {
                save()
            }
        } catch (e: Exception) {
            Premiere.LOGGER.warn("Could not read {}; using defaults", path, e)
        }
    }

    private fun save() {
        val path = FabricLoader.getInstance().configDir.resolve("premiere-client.json")
        try {
            Files.createDirectories(path.parent)
            val o = JsonObject().apply {
                addProperty("subtitles", subtitlesEnabled)
                addProperty("subtitle_language", subtitleLanguage)
            }
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(o))
        } catch (e: Exception) {
            Premiere.LOGGER.warn("Could not save client config", e)
        }
    }
}
