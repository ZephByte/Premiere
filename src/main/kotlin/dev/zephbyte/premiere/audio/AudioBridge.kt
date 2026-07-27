package dev.zephbyte.premiere.audio

import dev.zephbyte.premiere.screen.Playback
import dev.zephbyte.premiere.screen.ScreenDefinition
import net.minecraft.server.MinecraftServer

/**
 * Indirection between the screen manager and Simple Voice Chat. SVC is an
 * optional runtime dependency: this interface has no SVC types in it, and the
 * only implementation is constructed by SVC itself via the "voicechat"
 * entrypoint. When SVC is absent, [instance] stays null and movie night is
 * silent (which is the documented degraded experience), but nothing crashes.
 */
interface AudioBridge {
    fun onPlaybackChanged(server: MinecraftServer, screen: ScreenDefinition, playback: Playback)

    /**
     * Periodic drift audit: compares where the soundtrack actually is against
     * the master clock and rebuilds the session if they've truly diverged.
     * The audio path is otherwise open-loop, so this is its correction ping.
     */
    fun onSyncCheck(server: MinecraftServer, screen: ScreenDefinition, playback: Playback) {}

    fun onScreenRemoved(screenName: String)
    fun shutdownAll()

    companion object {
        @Volatile
        var instance: AudioBridge? = null
    }
}
