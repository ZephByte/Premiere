package dev.zephbyte.premiere.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.zephbyte.premiere.PremiereCore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Shared load/save for the mod's flat JSON config files. */
object JsonConfig {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Parses [path], or returns null if absent/unreadable (with a log). */
    fun read(path: Path): JsonObject? = try {
        readOrThrow(path)
    } catch (e: Exception) {
        PremiereCore.LOGGER.warn("Could not read {}; using defaults", path, e)
        null
    }

    /** Parses [path], returning null only when it does not exist. */
    fun readOrThrow(path: Path): JsonObject? =
        if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else null

    fun write(path: Path, populate: JsonObject.() -> Unit): Boolean =
        writeStringAtomic(path, gson.toJson(JsonObject().apply(populate)))

    /**
     * Writes through a sibling temporary file and atomically replaces the
     * destination where the filesystem supports it. A crash can therefore
     * leave either the old complete file or the new complete file, never a
     * partially truncated config.
     */
    fun writeStringAtomic(path: Path, contents: String): Boolean {
        var temporary: Path? = null
        return try {
            Files.createDirectories(path.parent)
            temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
            Files.writeString(temporary, contents)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
            true
        } catch (e: Exception) {
            PremiereCore.LOGGER.warn("Could not save {}", path, e)
            false
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
