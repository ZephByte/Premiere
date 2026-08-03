package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.client.video.VideoPlayer
import dev.zephbyte.premiere.net.ScreenStatePayload
import dev.zephbyte.premiere.toVec3
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition
import dev.zephbyte.premiere.util.MediaUrls
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import dev.zephbyte.premiere.client.subtitles.SubtitleStore
import dev.zephbyte.premiere.net.ScreenReadyPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

/** Client-side mirror of the server's screens, driven entirely by payloads. */
object ClientScreens {

    data class SubtitleAvailability(
        val sidecar: Boolean,
        val embeddedLanguages: List<String>,
    )

    class ActiveScreen(@Volatile var definition: ScreenDefinition) {
        @Volatile
        var state: PlayState = PlayState.STOPPED

        @Volatile
        var player: VideoPlayer? = null

        @Volatile
        var subtitleUrl: String = ""

        @Volatile
        var url: String = ""

        @Volatile
        var audioDistance: Float = 48f

        @Volatile
        var audioLanguage: String = ""

        @Volatile
        var generation: Int = 0

        @Volatile
        var mediaPositionMs: Long = 0

        @Volatile
        var receivedAtMs: Long = System.currentTimeMillis()

        @Volatile
        var volume: Float = 1f

        @Volatile
        var playerGeneration: Int = -1

        /** State last applied to the local decoder; used to distinguish a
         * real play/pause transition from a routine server heartbeat. */
        @Volatile
        var lastAppliedState: PlayState? = null

        @Volatile
        var retryAfterMs: Long = 0

        fun currentMediaMs(now: Long = System.currentTimeMillis()): Long =
            if (state == PlayState.PLAYING) mediaPositionMs + (now - receivedAtMs) else mediaPositionMs
    }

    /**
     * Do not open a network decoder just because a server has a playing screen.
     * Screens outside the player's current dimension or theater area remain
     * cheap snapshots until the player approaches them.
     */
    private const val MIN_ACTIVATION_DISTANCE = 128.0
    private const val DEACTIVATION_HYSTERESIS = 32.0
    private const val RETRY_DELAY_MS = 10_000L
    private val screens = ConcurrentHashMap<String, ActiveScreen>()

    // GPU textures must be released on the render thread; retired players are
    // parked here and drained by the renderer.
    private val retired = ConcurrentLinkedQueue<VideoPlayer>()

    fun handle(payload: ScreenStatePayload) {
        val msg = payload.msg
        val name = msg.screen.name
        if (msg.removed) {
            screens.remove(name)?.let { retire(it) }
            return
        }
        val active = screens.computeIfAbsent(name) { ActiveScreen(msg.screen) }
        val urlChanged = active.url != msg.url
        val generationChanged = active.generation != msg.generation
        active.definition = msg.screen
        active.state = msg.state
        active.subtitleUrl = msg.subtitleUrl
        active.url = msg.url
        active.audioDistance = msg.audioDistance
        active.audioLanguage = msg.audioLanguage
        active.generation = msg.generation
        active.mediaPositionMs = msg.mediaPositionMs
        active.receivedAtMs = System.currentTimeMillis()
        active.volume = msg.volume
        if (urlChanged || generationChanged) active.retryAfterMs = 0

        when (msg.state) {
            PlayState.STOPPED -> retire(active)
            PlayState.PLAYING, PlayState.PAUSED, PlayState.LOADED ->
                reconcile(active, sync = true, forceSeek = generationChanged)
        }
    }

    /** Client tick: activate nearby snapshots and retire remote decoders. */
    fun tick() {
        screens.values.forEach { reconcile(it, sync = false, forceSeek = false) }
    }

    private fun reconcile(active: ActiveScreen, sync: Boolean, forceSeek: Boolean) {
        if (active.state == PlayState.STOPPED) {
            retire(active)
            return
        }
        if (!shouldDecode(active)) {
            retire(active)
            return
        }

        MediaUrls.validate(active.url)?.let { error ->
            Premiere.LOGGER.warn("Rejecting broadcast URL for screen '{}': {}", active.definition.name, error)
            retire(active)
            active.retryAfterMs = Long.MAX_VALUE
            return
        }

        val now = System.currentTimeMillis()
        var player = active.player
        if (player != null && player.url != active.url) {
            // A deliberate movie change is not a decoder failure. Replace it
            // immediately; applying RETRY_DELAY_MS here made every /pm load
            // and URL switch sit on the spinner for exactly ten seconds.
            retire(active)
            active.retryAfterMs = 0
            player = null
        } else if (player != null && !player.isAlive) {
            retire(active)
            active.retryAfterMs = now + RETRY_DELAY_MS
            player = null
        }

        var created = false
        if (player == null) {
            if (now < active.retryAfterMs) return
            val name = active.definition.name
            val generation = active.generation
            player = VideoPlayer(
                name,
                active.url,
                active.definition.faceCenter().toVec3(),
                active.audioDistance,
                active.audioLanguage,
            ) { durationMs -> reportReady(name, generation, durationMs) }
            active.player = player
            active.playerGeneration = generation
            active.retryAfterMs = 0
            created = true
        }

        val generationChanged = active.playerGeneration != active.generation
        val stateChanged = active.lastAppliedState != active.state
        // Payloads are the only heartbeat source. Do not compare clocks every
        // client tick: tiny scheduling differences would repeatedly reset the
        // decoder and present as a one-second video hitch.
        if (created || sync || stateChanged || generationChanged) {
            player.updateSync(
                active.currentMediaMs(now),
                active.state == PlayState.PLAYING,
                // A newly created player may be joining mid-film; both
                // decoders must receive the initial seek epoch. Routine video
                // catch-up seeks are deliberately not global seeks.
                forceSeek = forceSeek || generationChanged || created,
            )
            active.playerGeneration = active.generation
            active.lastAppliedState = active.state
        }
        player.setVolume(active.volume)
    }

    private fun shouldDecode(active: ActiveScreen): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        val level = minecraft.level ?: return false
        if (active.definition.dimension != level.dimension().identifier().toString()) return false
        val base = maxOf(MIN_ACTIVATION_DISTANCE, active.audioDistance.toDouble())
        val limit = base + if (active.player != null) DEACTIVATION_HYSTERESIS else 0.0
        return player.position().distanceToSqr(active.definition.faceCenter().toVec3()) <= limit * limit
    }

    /** First frame decoded: if the screen is in LOADED, tell the server. */
    private fun reportReady(name: String, generation: Int, durationMs: Long) {
        val active = screens[name] ?: return
        if (active.generation != generation) return
        if (ClientPlayNetworking.canSend(ScreenReadyPayload.TYPE)) {
            ClientPlayNetworking.send(ScreenReadyPayload(name, generation, durationMs))
        }
    }

    fun renderable(): Collection<ActiveScreen> = screens.values

    /** Subtitle choices for the nearest decoded movie in this dimension. */
    fun nearestSubtitleAvailability(): SubtitleAvailability? {
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        val dimension = level.dimension().identifier().toString()
        return screens.values.asSequence()
            .filter { it.state != PlayState.STOPPED && it.definition.dimension == dimension }
            .mapNotNull { active ->
                val videoPlayer = active.player ?: return@mapNotNull null
                val languages = videoPlayer.availableSubtitleLanguages()
                if (active.subtitleUrl.isEmpty() && languages.isEmpty()) return@mapNotNull null
                val distance = localPlayer.position().distanceToSqr(active.definition.faceCenter().toVec3())
                distance to SubtitleAvailability(active.subtitleUrl.isNotEmpty(), languages)
            }
            .minByOrNull { it.first }
            ?.second
    }

    fun drainRetired(action: (VideoPlayer) -> Unit) {
        while (true) {
            val player = retired.poll() ?: return
            action(player)
        }
    }

    fun clear() {
        screens.values.forEach { retire(it) }
        screens.clear()
        SubtitleStore.clear()
    }

    private fun retire(active: ActiveScreen) {
        active.player?.let { retire(it) }
        active.player = null
        active.playerGeneration = -1
        active.lastAppliedState = null
    }

    private fun retire(player: VideoPlayer) {
        player.close() // stops the decode thread; texture freed on render thread
        retired.add(player)
    }
}
