package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.client.video.VideoPlayer
import dev.zephbyte.premiere.net.ScreenStatePayload
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import dev.zephbyte.premiere.util.MediaUrls
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Client-side mirror of the server's screens, driven entirely by payloads. */
object ClientScreens {

    class ActiveScreen(@Volatile var definition: ScreenDefinition) {
        @Volatile
        var state: PlayState = PlayState.STOPPED

        @Volatile
        var player: VideoPlayer? = null

        @Volatile
        var subtitleUrl: String = ""
    }

    private val screens = ConcurrentHashMap<String, ActiveScreen>()

    // GPU textures must be released on the render thread; retired players are
    // parked here and drained by the renderer.
    private val retired = ConcurrentLinkedQueue<VideoPlayer>()

    fun handle(payload: ScreenStatePayload) {
        val name = payload.screen.name
        if (payload.removed) {
            screens.remove(name)?.let { retire(it) }
            return
        }
        val active = screens.computeIfAbsent(name) { ActiveScreen(payload.screen) }
        active.definition = payload.screen
        active.state = payload.state
        active.subtitleUrl = payload.subtitleUrl

        when (payload.state) {
            PlayState.STOPPED -> retire(active)
            PlayState.PLAYING, PlayState.PAUSED, PlayState.LOADED -> {
                MediaUrls.validate(payload.url)?.let { error ->
                    Premiere.LOGGER.warn("Rejecting broadcast URL for screen '{}': {}", name, error)
                    retire(active)
                    return
                }
                var player = active.player
                // A dead decoder (error, bad stream) can't serve a replay of
                // the same URL; rebuild instead of reusing the frozen frame.
                if (player == null || player.url != payload.url || !player.isAlive) {
                    retire(active)
                    player = VideoPlayer(name, payload.url) { reportReady(name) }
                    active.player = player
                }
                // Every payload refreshes the sync anchor; the periodic server
                // rebroadcast is what keeps long-running playback drift-free.
                // LOADED behaves like paused-at-zero: decode one frame, park.
                player.updateSync(payload.mediaPositionMs, payload.state == PlayState.PLAYING)
            }
        }
    }

    /** First frame decoded: if the screen is in LOADED, tell the server. */
    private fun reportReady(name: String) {
        val active = screens[name] ?: return
        if (active.state != PlayState.LOADED) return
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.zephbyte.premiere.net.ScreenReadyPayload.TYPE)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                dev.zephbyte.premiere.net.ScreenReadyPayload(name)
            )
        }
    }

    fun renderable(): Collection<ActiveScreen> = screens.values

    fun drainRetired(action: (VideoPlayer) -> Unit) {
        while (true) {
            val player = retired.poll() ?: return
            action(player)
        }
    }

    fun clear() {
        screens.values.forEach { retire(it) }
        screens.clear()
        dev.zephbyte.premiere.client.subtitles.SubtitleStore.clear()
    }

    private fun retire(active: ActiveScreen) {
        active.player?.let { retire(it) }
        active.player = null
    }

    private fun retire(player: VideoPlayer) {
        player.close() // stops the decode thread; texture freed on render thread
        retired.add(player)
    }
}
