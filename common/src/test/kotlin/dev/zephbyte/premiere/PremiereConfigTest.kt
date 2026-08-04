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
        val previousFullVolumeRadius = PremiereConfig.audioFullVolumeRadius
        val path = directory.resolve("premiere.json")
        val malformed = """{"audio_distance": }"""
        Files.writeString(path, malformed)

        assertFalse(PremiereConfig.reload())
        assertEquals(previousDistance, PremiereConfig.audioDistance)
        assertEquals(previousFullVolumeRadius, PremiereConfig.audioFullVolumeRadius)
        assertEquals(malformed, Files.readString(path))
    }

    @Test
    fun `older config derives a valid full volume radius`() {
        Files.writeString(directory.resolve("premiere.json"), """{"audio_distance":12}""")

        PremiereConfig.load(directory)

        assertEquals(4f, PremiereConfig.audioFullVolumeRadius)
    }

    @Test
    fun `full volume radius must remain inside outer audio distance`() {
        PremiereConfig.load(directory)
        val previousDistance = PremiereConfig.audioDistance
        val previousRadius = PremiereConfig.audioFullVolumeRadius
        val path = directory.resolve("premiere.json")
        val invalid = """{"audio_distance":48,"audio_full_volume_radius":48}"""
        Files.writeString(path, invalid)

        assertFalse(PremiereConfig.reload())
        assertEquals(previousDistance, PremiereConfig.audioDistance)
        assertEquals(previousRadius, PremiereConfig.audioFullVolumeRadius)
        assertEquals(invalid, Files.readString(path))
    }
}
