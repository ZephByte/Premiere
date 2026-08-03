package dev.zephbyte.premiere.util

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JsonConfigTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `atomic write produces a complete readable object`() {
        val path = directory.resolve("premiere.json")

        assertTrue(JsonConfig.write(path) {
            addProperty("audio_distance", 48)
            addProperty("audio_language", "eng")
        })

        val parsed = JsonConfig.readOrThrow(path)!!
        assertEquals(48, parsed["audio_distance"].asInt)
        assertEquals("eng", parsed["audio_language"].asString)
        assertEquals(emptyList(), Files.list(directory).use { entries ->
            entries.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        })
    }

    @Test
    fun `invalid JSON is never rewritten by the reader`() {
        val path = directory.resolve("premiere.json")
        val malformed = """{"audio_distance": }"""
        Files.writeString(path, malformed)

        assertFails { JsonConfig.readOrThrow(path) }
        assertEquals(malformed, Files.readString(path))
    }
}
