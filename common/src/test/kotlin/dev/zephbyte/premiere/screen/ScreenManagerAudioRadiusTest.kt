package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.platform.PlayerHandle
import dev.zephbyte.premiere.platform.PremierePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ScreenManagerAudioRadiusTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `screen radius survives restart and redefine until reset to config default`() {
        PremiereConfig.load(directory.resolve("config"))
        val platform = TestPlatform(directory.resolve("premiere_screens.json"))
        val definition = ScreenDefinition(
            name = "theater",
            dimension = "minecraft:overworld",
            origin = ScreenPos(10, 64, 20),
            width = 8,
            height = 5,
            facing = ScreenFacing.SOUTH,
        )

        ScreenManager.start(platform)
        try {
            val overrideRadius = PremiereConfig.audioDistance / 2f
            assertTrue(ScreenManager.define(definition))
            val screen = requireNotNull(ScreenManager.get("theater"))
            assertEquals(PremiereConfig.audioFullVolumeRadius, screen.effectiveAudioFullVolumeRadius())

            assertTrue(ScreenManager.setAudioFullVolumeRadius(screen, overrideRadius))
            assertEquals(overrideRadius, screen.audioFullVolumeRadiusOverride)
            assertContains(Files.readString(platform.screensFile), "\"audio_full_volume_radius\"")

            ScreenManager.start(platform)
            assertEquals(overrideRadius, ScreenManager.get("theater")?.audioFullVolumeRadiusOverride)

            assertTrue(ScreenManager.redefine(definition.copy(height = 6)))
            val redefined = requireNotNull(ScreenManager.get("theater"))
            assertEquals(overrideRadius, redefined.audioFullVolumeRadiusOverride)

            assertTrue(ScreenManager.setAudioFullVolumeRadius(redefined, null))
            assertNull(redefined.audioFullVolumeRadiusOverride)
            assertEquals(PremiereConfig.audioFullVolumeRadius, redefined.effectiveAudioFullVolumeRadius())
            assertFalse(Files.readString(platform.screensFile).contains("audio_full_volume_radius"))
        } finally {
            ScreenManager.stop()
        }
    }

    private class TestPlatform(override val screensFile: Path) : PremierePlatform {
        override fun runOnServerThread(task: () -> Unit) = task()
        override fun onlinePlayers(): List<PlayerHandle> = emptyList()
        override fun player(uuid: UUID): PlayerHandle? = null
    }
}
