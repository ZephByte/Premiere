package dev.zephbyte.premiere.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.zephbyte.premiere.PremiereCore
import java.nio.file.Files
import java.nio.file.Path

/** Shared load/save for the mod's flat JSON config files. */
object JsonConfig {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Parses [path], or returns null if absent/unreadable (with a log). */
    fun read(path: Path): JsonObject? = try {
        if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else null
    } catch (e: Exception) {
        PremiereCore.LOGGER.warn("Could not read {}; using defaults", path, e)
        null
    }

    fun write(path: Path, populate: JsonObject.() -> Unit) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(JsonObject().apply(populate)))
        } catch (e: Exception) {
            PremiereCore.LOGGER.warn("Could not save {}", path, e)
        }
    }
}
