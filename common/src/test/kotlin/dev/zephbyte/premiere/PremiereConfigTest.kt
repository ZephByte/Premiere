package dev.zephbyte.premiere

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class PremiereConfigTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `malformed reload preserves both active values and file contents`() {
        PremiereConfig.load(directory)
        val previousDistance = PremiereConfig.audioDistance
        val path = directory.resolve("premiere.json")
        val malformed = """{"audio_distance": }"""
        Files.writeString(path, malformed)

        assertFalse(PremiereConfig.reload())
        assertEquals(previousDistance, PremiereConfig.audioDistance)
        assertEquals(malformed, Files.readString(path))
    }
}
