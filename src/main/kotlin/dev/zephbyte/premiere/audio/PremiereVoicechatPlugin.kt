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

        /** Audit tolerance: SVC's own jitter is ~100-300ms; past this, rebuild. */
        private const val DRIFT_REBUILD_MS = 750L
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
        val existing = sessions[screen.name]
        val resumable = existing != null && existing.generation == playback.generation &&
            existing.url == playback.url && !existing.streamer.finished

        when (playback.state) {
            PlayState.LOADED -> {
                sessions.remove(screen.name)?.close()
                // Prime: the streamer opens the URL, seeks, and fills its
                // buffer now, so audio starts the instant staff hits play.
                val streamer = newStreamer(playback)
                sessions[screen.name] = Session(streamer, playback.url, playback.generation)
            }

            PlayState.PLAYING -> {
                if (resumable) {
                    // Same film, same run: attach a player to the parked
                    // streamer (primed load, or a pause whose decoder and
                    // buffered queue we deliberately kept). Instant and
                    // position-exact — the queue holds the frames from the
                    // exact point consumption stopped.
                    if (existing!!.player?.isPlaying != true) {
                        attachPlayer(server, screen, existing)
                    }
                    // else: e.g. a volume push mid-play; gain is live, nothing to do
                } else {
                    sessions.remove(screen.name)?.close()
                    startCold(server, screen, playback)
                }
            }

            PlayState.PAUSED -> {
                if (resumable) {
                    existing!!.detachPlayer() // keep decoder + buffer parked
                } else {
                    sessions.remove(screen.name)?.close()
                }
            }

            PlayState.STOPPED -> sessions.remove(screen.name)?.close()
        }
    }

    override fun onSyncCheck(server: MinecraftServer, screen: ScreenDefinition, playback: Playback) {
        if (playback.state != PlayState.PLAYING) return
        val session = sessions[screen.name] ?: return
        if (session.streamer.finished) return // film's audio ended; nothing to correct
        val estimated = session.streamer.estimatedPositionMs() ?: return // still opening
        val target = playback.currentPositionMs() + dev.zephbyte.premiere.PremiereConfig.audioLeadMs
        val drift = estimated - target
        if (Math.abs(drift) > DRIFT_REBUILD_MS) {
            Premiere.LOGGER.info("Audio on '{}' drifted {}ms; rebuilding session", screen.name, drift)
            sessions.remove(screen.name)?.close()
            startCold(server, screen, playback)
        }
    }

    private fun newStreamer(playback: Playback): AudioStreamer = AudioStreamer(
        playback.url,
        { playback.currentPositionMs() },
        { playback.volume },
        playback.audioLanguage.ifBlank { dev.zephbyte.premiere.PremiereConfig.audioLanguage },
    )

    private fun startCold(server: MinecraftServer, screen: ScreenDefinition, playback: Playback) {
        val streamer = newStreamer(playback)
        val session = Session(streamer, playback.url, playback.generation)
        sessions[screen.name] = session
        if (!attachPlayer(server, screen, session)) {
            sessions.remove(screen.name)?.close()
        }
    }

    private fun attachPlayer(server: MinecraftServer, screen: ScreenDefinition, session: Session): Boolean {
        val api = serverApi ?: run {
            Premiere.LOGGER.warn("Voice chat server not started yet; no audio for '{}'", screen.name)
            return false
        }
        var channel = session.channel
        if (channel == null) {
            val levelKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(screen.dimension))
            val level = server.getLevel(levelKey) ?: run {
                Premiere.LOGGER.warn("Dimension {} not found for screen '{}'", screen.dimension, screen.name)
                return false
            }
            val center = screen.faceCenter()
            channel = api.createLocationalAudioChannel(
                UUID.randomUUID(),
                api.fromServerLevel(level),
                api.createPosition(center.x, center.y, center.z),
            ) ?: run {
                Premiere.LOGGER.warn("Could not create audio channel for '{}'", screen.name)
                return false
            }
            channel.setCategory(CATEGORY_ID)
            // Audible across a large theater room; SVC applies distance falloff.
            channel.setDistance(dev.zephbyte.premiere.PremiereConfig.audioDistance)
            session.channel = channel
        }
        val player = api.createAudioPlayer(channel, api.createEncoder(), session.streamer::nextFrame)
        session.player = player
        player.startPlaying()
        return true
    }

    override fun onScreenRemoved(screenName: String) {
        stopSession(screenName)
    }

    override fun shutdownAll() {
        sessions.keys.toList().forEach(::stopSession)
    }

    private fun stopSession(screenName: String) {
        sessions.remove(screenName)?.close()
    }

    /**
     * One screen's audio: decoder + (once audible) SVC channel and player.
     * [player] is null while primed or paused; the decoder and its buffered
     * queue survive both so starting is instant and position-exact.
     */
    private class Session(val streamer: AudioStreamer, val url: String, val generation: Int) {
        var channel: de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel? = null
        var player: AudioPlayer? = null

        fun detachPlayer() {
            runCatching { player?.stopPlaying() }
            player = null
        }

        fun close() {
            detachPlayer()
            streamer.close()
        }
    }
}
