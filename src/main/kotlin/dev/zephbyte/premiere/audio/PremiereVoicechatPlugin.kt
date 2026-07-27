package dev.zephbyte.premiere.audio

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.Playback
import dev.zephbyte.premiere.screen.ScreenDefinition
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Loaded only when Simple Voice Chat discovers the "voicechat" entrypoint, so
 * SVC classes never resolve on a server without it. The server decodes the
 * film's audio centrally (one authoritative timeline, zero per-client drift)
 * and SVC handles Opus, UDP transport, and 3D positional falloff.
 */
class PremiereVoicechatPlugin : VoicechatPlugin, AudioBridge {

    companion object {
        private const val CATEGORY_ID = "movies"
    }

    private var serverApi: VoicechatServerApi? = null
    private val sessions = ConcurrentHashMap<String, Session>()

    override fun getPluginId(): String = Premiere.MOD_ID

    override fun initialize(api: VoicechatApi) {
        AudioBridge.instance = this
        Premiere.LOGGER.info("Simple Voice Chat detected; movie audio enabled")
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { event ->
            val api = event.voicechat
            serverApi = api
            api.registerVolumeCategory(
                api.volumeCategoryBuilder()
                    .setId(CATEGORY_ID)
                    .setName("Movies")
                    .setDescription("Movie night screens")
                    .build()
            )
        }
    }

    override fun onPlaybackChanged(server: MinecraftServer, screen: ScreenDefinition, playback: Playback) {
        // Sessions are rebuilt rather than seeked: pause/resume/volume land here
        // rarely, and a fresh grabber at the master-clock position is simpler
        // and more robust than nudging a live decoder.
        stopSession(screen.name)
        if (playback.state != PlayState.PLAYING) return
        val api = serverApi ?: run {
            Premiere.LOGGER.warn("Voice chat server not started yet; no audio for '{}'", screen.name)
            return
        }

        val levelKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(screen.dimension))
        val level = server.getLevel(levelKey) ?: run {
            Premiere.LOGGER.warn("Dimension {} not found for screen '{}'", screen.dimension, screen.name)
            return
        }

        val center = screen.faceCenter()
        val channel = api.createLocationalAudioChannel(
            UUID.randomUUID(),
            api.fromServerLevel(level),
            api.createPosition(center.x, center.y, center.z),
        ) ?: run {
            Premiere.LOGGER.warn("Could not create audio channel for '{}'", screen.name)
            return
        }
        channel.setCategory(CATEGORY_ID)
        // Audible across a large theater room; SVC applies distance falloff.
        channel.setDistance(dev.zephbyte.premiere.PremiereConfig.audioDistance)

        val streamer = AudioStreamer(playback.url, { playback.currentPositionMs() }) { playback.volume }
        val player = api.createAudioPlayer(channel, api.createEncoder(), streamer::nextFrame)
        sessions[screen.name] = Session(player, streamer)
        player.startPlaying()
    }

    override fun onScreenRemoved(screenName: String) {
        stopSession(screenName)
    }

    override fun shutdownAll() {
        sessions.keys.toList().forEach(::stopSession)
    }

    private fun stopSession(screenName: String) {
        sessions.remove(screenName)?.let {
            runCatching { it.player.stopPlaying() }
            it.streamer.close()
        }
    }

    private class Session(val player: AudioPlayer, val streamer: AudioStreamer)
}
