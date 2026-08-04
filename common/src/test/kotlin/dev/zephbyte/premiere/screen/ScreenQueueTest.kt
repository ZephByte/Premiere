package dev.zephbyte.premiere.screen

import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.platform.PlayerHandle
import dev.zephbyte.premiere.platform.PremierePlatform
import dev.zephbyte.premiere.wire.ScreenStateMessage
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ScreenQueueTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `finished playback advances through queue then stops`() {
        val platform = TestPlatform(directory.resolve("premiere_screens.json"))
        ScreenManager.start(platform)
        try {
            assertTrue(
                ScreenManager.define(
                    ScreenDefinition(
                        "theater",
                        "minecraft:overworld",
                        ScreenPos(0, 64, 0),
                        8,
                        5,
                        ScreenFacing.SOUTH,
                    ),
                ),
            )
            val screen = requireNotNull(ScreenManager.get("theater"))
            screen.playback.play("https://example.com/current.mp4", "Current")
            ScreenManager.enqueue(screen, QueuedMedia("https://example.com/one.mp4", "One"))
            ScreenManager.enqueue(screen, QueuedMedia("https://example.com/two.mp4", "Two"))

            finish(screen)
            assertEquals("One", screen.playback.label)
            assertEquals(PlayState.LOADED, screen.playback.state)
            assertEquals(listOf("Two"), screen.queue.map { it.label })
            ready(screen)
            assertEquals(PlayState.PLAYING, screen.playback.state)

            finish(screen)
            assertEquals("Two", screen.playback.label)
            assertEquals(PlayState.LOADED, screen.playback.state)
            assertTrue(screen.queue.isEmpty())
            ready(screen)
            assertEquals(PlayState.PLAYING, screen.playback.state)

            finish(screen)
            assertEquals(PlayState.STOPPED, screen.playback.state)
        } finally {
            ScreenManager.stop()
        }
    }

    @Test
    fun `queue entries can be removed or cleared`() {
        val screen = ManagedScreen(
            ScreenDefinition(
                "theater",
                "minecraft:overworld",
                ScreenPos(0, 64, 0),
                8,
                5,
                ScreenFacing.SOUTH,
            ),
        )
        ScreenManager.enqueue(screen, QueuedMedia("https://example.com/one.mp4", "One"))
        ScreenManager.enqueue(screen, QueuedMedia("https://example.com/two.mp4", "Two"))

        assertEquals("One", ScreenManager.removeQueued(screen, 0)?.label)
        assertEquals(1, ScreenManager.clearQueue(screen))
        assertTrue(screen.queue.isEmpty())
    }

    @Test
    fun `loaded movie cannot roll before a client is ready`() {
        val screen = ManagedScreen(
            ScreenDefinition(
                "theater",
                "minecraft:overworld",
                ScreenPos(0, 64, 0),
                8,
                5,
                ScreenFacing.SOUTH,
            ),
        )
        screen.playback.load("https://example.com/movie.mp4", "Movie")

        assertFalse(ScreenManager.start(screen))
        assertEquals(PlayState.LOADED, screen.playback.state)

        screen.readyNotified = true
        assertTrue(ScreenManager.start(screen))
        assertEquals(PlayState.PLAYING, screen.playback.state)
    }

    @Test
    fun `queued movie waits for every nearby viewer before rolling`() {
        val first = TestPlayer(UUID.randomUUID(), "First")
        val second = TestPlayer(UUID.randomUUID(), "Second")
        val platform = TestPlatform(directory.resolve("premiere_screens.json"), listOf(first, second))
        ScreenManager.start(platform)
        try {
            assertTrue(
                ScreenManager.define(
                    ScreenDefinition(
                        "theater",
                        "minecraft:overworld",
                        ScreenPos(0, 64, 0),
                        8,
                        5,
                        ScreenFacing.SOUTH,
                    ),
                ),
            )
            val screen = requireNotNull(ScreenManager.get("theater"))
            ScreenManager.enqueue(screen, QueuedMedia("https://example.com/movie.mp4", "Movie"))
            assertTrue(ScreenManager.loadNext(screen))

            ScreenManager.clientReportedReady("theater", screen.playback.generation, 1_000, first.uuid)
            assertEquals(PlayState.LOADED, screen.playback.state)
            assertFalse(screen.readyNotified)

            ScreenManager.clientReportedReady("theater", screen.playback.generation, 1_000, second.uuid)
            assertEquals(PlayState.PLAYING, screen.playback.state)
        } finally {
            ScreenManager.stop()
        }
    }

    private fun finish(screen: ManagedScreen) {
        screen.playback.durationMs = 1_000
        screen.playback.seek(1_000)
        ScreenManager.tick()
    }

    private fun ready(screen: ManagedScreen) {
        ScreenManager.clientReportedReady(
            screen.definition.name,
            screen.playback.generation,
            1_000,
            UUID.randomUUID(),
        )
    }

    private class TestPlatform(
        override val screensFile: Path,
        private val players: List<PlayerHandle> = emptyList(),
    ) : PremierePlatform {
        override fun runOnServerThread(task: () -> Unit) = task()
        override fun onlinePlayers(): List<PlayerHandle> = players
        override fun player(uuid: UUID): PlayerHandle? = players.firstOrNull { it.uuid == uuid }
    }

    private class TestPlayer(
        override val uuid: UUID,
        override val name: String,
    ) : PlayerHandle {
        override val dimension = "minecraft:overworld"
        override val position = Vec3d(0.0, 64.0, 0.0)
        override val canReceiveScreenState = true
        override fun sendScreenState(msg: ScreenStateMessage) = Unit
        override fun sendChat(text: String) = Unit
        override fun sendActionBar(text: String) = Unit
        override fun hasControlPermission() = true
    }
}
