package dev.zephbyte.premiere.screen

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.PremiereCore
import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.geo.ScreenPos
import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.platform.PremierePlatform
import dev.zephbyte.premiere.util.JsonConfig
import dev.zephbyte.premiere.wire.ScreenStateMessage
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ManagedScreen(
    val definition: ScreenDefinition,
    var audioFullVolumeRadiusOverride: Float? = null,
) {
    val playback = Playback()
    val queue = ArrayDeque<QueuedMedia>()

    fun effectiveAudioFullVolumeRadius(): Float {
        val maximum = Math.nextDown(PremiereConfig.audioDistance).coerceAtLeast(0f)
        return (audioFullVolumeRadiusOverride ?: PremiereConfig.audioFullVolumeRadius)
            .coerceIn(0f, maximum)
    }

    /** Who ran /pm load, for the "it's buffered" ping. */
    var loaderUuid: UUID? = null
    var readyNotified = false
    val awaitingReady = mutableSetOf<UUID>()
    val readyClients = mutableSetOf<UUID>()
    var autoStartWhenReady = false
}

data class QueuedMedia(
    val url: String,
    val label: String,
    val subtitleUrl: String = "",
    val audioLanguage: String = "",
)

/**
 * Server-side registry of screens, platform-free: everything server-specific
 * comes through [PremierePlatform]. Geometry is persisted as plain JSON in the
 * world folder, deliberately independent of the physical wall blocks (which can
 * be griefed or world-edited away without corrupting anything). Playback state
 * is not persisted; a restart mid-film means staff replays.
 */
object ScreenManager {
    // Mutated on the server thread only, but read from command-suggestion and
    // dashboard threads — hence concurrent. Views are name-sorted for output.
    private val screens = ConcurrentHashMap<String, ManagedScreen>()
    private var platform: PremierePlatform? = null

    /**
     * Rebroadcast cadence (ticks) for playing screens; doubles as drift
     * correction. Each platform owns its own tick hook and calls
     * [rebroadcastPlaying] at this interval.
     */
    const val REBROADCAST_TICKS = 200

    /** Must match the client decoder activation range. */
    const val CLIENT_ACTIVATION_DISTANCE = 128.0

    /** Server is up; load geometry and start serving. */
    fun start(platform: PremierePlatform) {
        this.platform = platform
        load()
    }

    fun stop() {
        platform = null
    }

    /** Called by the platform tick hook every [REBROADCAST_TICKS] ticks. */
    fun rebroadcastPlaying() {
        screens.values.filter { it.playback.state == PlayState.PLAYING }
            .forEach { broadcast(it) }
    }

    /** Advances finished screens promptly; called once per server tick. */
    fun tick() {
        for (screen in screens.values) {
            val playback = screen.playback
            if (playback.state == PlayState.LOADED && screen.autoStartWhenReady) {
                pruneUnavailableViewers(screen)
                if (screen.readyClients.isNotEmpty() && screen.awaitingReady.isEmpty()) {
                    finishPreparing(screen, reporterName = null)
                }
                continue
            }
            if (playback.state != PlayState.PLAYING || playback.durationMs <= 0) continue
            if (playback.currentPositionMs() < playback.durationMs) continue
            if (!loadNext(screen)) stop(screen)
        }
    }

    fun all(): List<ManagedScreen> = screens.values.sortedBy { it.definition.name }

    fun get(name: String): ManagedScreen? = screens[name]

    fun nearestTo(dimension: String, position: Vec3d): ManagedScreen? =
        screens.values.filter { it.definition.dimension == dimension }
            .minByOrNull { it.definition.faceCenter().distanceSqrTo(position) }

    fun single(): ManagedScreen? = screens.values.singleOrNull()

    /**
     * Dashboard bridge: runs [action] on the server thread against a named
     * screen and hands back a user-readable error, or null on success.
     */
    fun dashboardAction(
        name: String,
        action: (ManagedScreen) -> String?,
    ): CompletableFuture<String?> {
        val platform = this.platform
            ?: return CompletableFuture.completedFuture("Server not ready")
        val future = CompletableFuture<String?>()
        platform.runOnServerThread {
            val screen = screens[name]
            if (screen == null) {
                future.complete("No screen named '$name'")
            } else {
                future.complete(runCatching { action(screen) }.getOrElse { it.message ?: "error" })
            }
        }
        return future
    }

    fun define(definition: ScreenDefinition): Boolean {
        if (screens.containsKey(definition.name)) return false
        val screen = ManagedScreen(definition)
        screens[definition.name] = screen
        if (!save()) {
            screens.remove(definition.name, screen)
            return false
        }
        broadcast(screen)
        return true
    }

    /**
     * Atomically replaces an existing definition in memory. Playback is
     * intentionally stopped, and clients receive one coherent replacement
     * snapshot instead of a remove followed by a possibly-failing define.
     */
    fun redefine(definition: ScreenDefinition): Boolean {
        val old = screens[definition.name] ?: return false
        val replacement = ManagedScreen(definition, old.audioFullVolumeRadiusOverride)
        screens[definition.name] = replacement
        if (!save()) {
            screens[definition.name] = old
            return false
        }
        old.playback.stop()
        broadcast(replacement)
        return true
    }

    fun undefine(name: String): Boolean {
        val screen = screens.remove(name) ?: return false
        if (!save()) {
            screens[name] = screen
            return false
        }
        screen.playback.stop()
        sendToAll(messageFor(screen, removed = true))
        return true
    }

    fun load(
        screen: ManagedScreen,
        url: String,
        label: String,
        subtitleUrl: String,
        audioLanguage: String,
        loaderUuid: UUID?,
    ) {
        screen.playback.load(url, label, subtitleUrl, audioLanguage)
        screen.loaderUuid = loaderUuid
        screen.readyNotified = false
        screen.autoStartWhenReady = false
        screen.readyClients.clear()
        screen.awaitingReady.clear()
        eligibleViewers(screen).mapTo(screen.awaitingReady) { it.uuid }
        broadcast(screen)
    }

    /** Starts a LOADED screen (or resumes a PAUSED one). */
    fun start(screen: ManagedScreen): Boolean {
        if (screen.playback.state == PlayState.LOADED && !screen.readyNotified) return false
        screen.autoStartWhenReady = false
        screen.awaitingReady.clear()
        screen.readyClients.clear()
        screen.playback.resume()
        broadcast(screen)
        return true
    }

    fun seek(screen: ManagedScreen, toMs: Long) {
        screen.playback.seek(toMs)
        broadcast(screen)
    }

    /** A video client decoded its first frame of a LOADED screen. */
    fun clientReportedReady(screenName: String, generation: Int, durationMs: Long, reporterUuid: UUID) {
        val screen = screens[screenName] ?: return
        if (screen.playback.generation != generation) return
        if (durationMs > 0) screen.playback.durationMs = durationMs
        if (screen.playback.state != PlayState.LOADED ||
            screen.readyNotified
        ) return
        val reporterName = platform?.player(reporterUuid)?.name ?: "a viewer"
        screen.readyClients += reporterUuid
        screen.awaitingReady -= reporterUuid
        pruneUnavailableViewers(screen)
        if (screen.awaitingReady.isNotEmpty()) return
        finishPreparing(screen, reporterName)
    }

    /** Returns true if now playing, false if now paused. */
    fun togglePause(screen: ManagedScreen): Boolean {
        val playback = screen.playback
        if (playback.state == PlayState.PAUSED) playback.resume() else playback.pause()
        broadcast(screen)
        return playback.state == PlayState.PLAYING
    }

    fun stop(screen: ManagedScreen) {
        screen.autoStartWhenReady = false
        screen.readyNotified = false
        screen.awaitingReady.clear()
        screen.readyClients.clear()
        screen.playback.stop()
        broadcast(screen)
    }

    fun setVolume(screen: ManagedScreen, volume: Float) {
        screen.playback.volume = volume
        broadcast(screen)
    }

    fun enqueue(screen: ManagedScreen, media: QueuedMedia): Int {
        screen.queue.addLast(media)
        return screen.queue.size
    }

    fun removeQueued(screen: ManagedScreen, index: Int): QueuedMedia? {
        if (index !in 0 until screen.queue.size) return null
        val iterator = screen.queue.iterator()
        repeat(index) { iterator.next() }
        val removed = iterator.next()
        iterator.remove()
        return removed
    }

    fun clearQueue(screen: ManagedScreen): Int {
        val removed = screen.queue.size
        screen.queue.clear()
        return removed
    }

    /** Loads the next queued movie and rolls only after the audience is ready. */
    fun loadNext(screen: ManagedScreen, loaderUuid: UUID? = null): Boolean {
        val next = screen.queue.pollFirst() ?: return false
        load(screen, next.url, next.label, next.subtitleUrl, next.audioLanguage, loaderUuid)
        screen.autoStartWhenReady = true
        return true
    }

    private fun finishPreparing(screen: ManagedScreen, reporterName: String?) {
        if (screen.playback.state != PlayState.LOADED || screen.readyNotified) return
        screen.readyNotified = true
        if (screen.autoStartWhenReady) {
            PremiereCore.LOGGER.info(
                "Screen '{}' finished buffering '{}' for {} viewer(s); rolling queued playback",
                screen.definition.name,
                screen.playback.label,
                screen.readyClients.size,
            )
            start(screen)
            return
        }
        val loader = screen.loaderUuid?.let { uuid -> platform?.player(uuid) }
        val readyOn = if (screen.readyClients.size > 1) "${screen.readyClients.size} viewers" else reporterName ?: "a viewer"
        if (loader != null) {
            loader.sendLoadReady(screen.playback.label, readyOn, screen.definition.name)
        } else {
            PremiereCore.LOGGER.info("Screen '{}' buffered for {}", screen.definition.name, readyOn)
        }
    }

    private fun eligibleViewers(screen: ManagedScreen): List<dev.zephbyte.premiere.platform.PlayerHandle> {
        return platform?.onlinePlayers().orEmpty().filter { isEligibleViewer(screen, it) }
    }

    private fun isEligibleViewer(
        screen: ManagedScreen,
        player: dev.zephbyte.premiere.platform.PlayerHandle,
    ): Boolean {
        val range = maxOf(CLIENT_ACTIVATION_DISTANCE, PremiereConfig.audioDistance.toDouble())
        return player.canReceiveScreenState &&
            player.dimension == screen.definition.dimension &&
            player.position.distanceSqrTo(screen.definition.faceCenter()) <= range * range
    }

    private fun pruneUnavailableViewers(screen: ManagedScreen) {
        val eligible = eligibleViewers(screen).mapTo(mutableSetOf()) { it.uuid }
        screen.awaitingReady.retainAll(eligible)
    }

    /** Persists a per-screen audience radius, or null to follow the config default. */
    fun setAudioFullVolumeRadius(screen: ManagedScreen, radius: Float?): Boolean {
        require(radius == null || radius.isFinite() && radius >= 0f && radius < PremiereConfig.audioDistance) {
            "Audience radius must be at least 0 and less than audio_distance (${PremiereConfig.audioDistance})"
        }
        val previous = screen.audioFullVolumeRadiusOverride
        screen.audioFullVolumeRadiusOverride = radius
        if (!save()) {
            screen.audioFullVolumeRadiusOverride = previous
            return false
        }
        broadcast(screen)
        return true
    }

    /** Plain-types snapshot of one screen for the staff dashboard. */
    data class ScreenStatus(
        val name: String,
        val size: String,
        val facing: String,
        val state: String,
        val label: String,
        /** Authorized dashboard preview source; never written to logs or HTML. */
        val url: String,
        val generation: Int,
        val ready: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volumePercent: Int,
        val audioDistance: Float,
        val audioFullVolumeRadius: Float,
        val audioFullVolumeRadiusIsDefault: Boolean,
        val queue: List<QueueStatus>,
    )

    data class QueueStatus(val label: String)

    /**
     * Dashboard snapshot, completed on the server thread so HTTP threads never
     * touch live game state directly.
     */
    fun statusSnapshot(): CompletableFuture<List<ScreenStatus>> {
        val platform = this.platform
            ?: return CompletableFuture.completedFuture(emptyList())
        val future = CompletableFuture<List<ScreenStatus>>()
        platform.runOnServerThread {
            future.complete(screens.values.map { screen ->
                val d = screen.definition
                val p = screen.playback
                ScreenStatus(
                    name = d.name,
                    size = "${d.width}x${d.height}",
                    facing = d.facing.serializedName,
                    state = p.state.name,
                    label = p.label,
                    url = p.url,
                    generation = p.generation,
                    ready = screen.readyNotified,
                    positionMs = p.currentPositionMs(),
                    durationMs = p.durationMs,
                    volumePercent = (p.volume * 100).toInt(),
                    audioDistance = PremiereConfig.audioDistance,
                    audioFullVolumeRadius = screen.effectiveAudioFullVolumeRadius(),
                    audioFullVolumeRadiusIsDefault = screen.audioFullVolumeRadiusOverride == null,
                    queue = screen.queue.map { QueueStatus(it.label) },
                )
            })
        }
        return future
    }

    /**
     * Late joiner / channel registration: send the full current state.
     * Deliberately not gated on canReceiveScreenState — this only ever fires
     * in response to the client's own request payload, which vanilla clients
     * can't send.
     */
    fun sendAllTo(playerUuid: UUID) {
        val player = platform?.player(playerUuid) ?: return
        screens.values.forEach { screen ->
            if (screen.playback.state == PlayState.LOADED &&
                !screen.readyNotified &&
                isEligibleViewer(screen, player)
            ) {
                // A viewer who joins during preparation belongs to the same
                // readiness barrier as everyone online at load time.
                screen.awaitingReady += player.uuid
            }
            player.sendScreenState(messageFor(screen))
        }
    }

    private fun broadcast(screen: ManagedScreen) {
        sendToAll(messageFor(screen))
    }

    private fun sendToAll(msg: ScreenStateMessage) {
        val platform = this.platform ?: return
        for (player in platform.onlinePlayers()) {
            // Modded clients only; everyone else never sees the channel.
            if (player.canReceiveScreenState) {
                player.sendScreenState(msg)
            }
        }
    }

    private fun messageFor(screen: ManagedScreen, removed: Boolean = false): ScreenStateMessage {
        val playback = screen.playback
        return ScreenStateMessage(
            screen = screen.definition,
            url = playback.url,
            subtitleUrl = playback.subtitleUrl,
            audioLanguage = playback.audioLanguage.ifBlank { PremiereConfig.audioLanguage },
            audioDistance = PremiereConfig.audioDistance,
            audioFullVolumeRadius = screen.effectiveAudioFullVolumeRadius(),
            state = playback.state,
            generation = playback.generation,
            mediaPositionMs = playback.currentPositionMs(),
            volume = playback.volume,
            removed = removed,
        )
    }

    // --- persistence ---

    private fun load() {
        val platform = this.platform ?: return
        screens.clear()
        val path = platform.screensFile
        if (!Files.exists(path)) return
        try {
            val root = JsonParser.parseString(Files.readString(path)).asJsonObject
            for (element in root.getAsJsonArray("screens")) {
                val o = element.asJsonObject
                val definition = ScreenDefinition(
                    name = o["name"].asString,
                    dimension = o["dimension"].asString,
                    origin = ScreenPos(o["x"].asInt, o["y"].asInt, o["z"].asInt),
                    width = o["width"].asInt,
                    height = o["height"].asInt,
                    facing = ScreenFacing.byName(o["facing"].asString) ?: ScreenFacing.NORTH,
                )
                val radius = o["audio_full_volume_radius"]?.let { value ->
                    runCatching { value.asFloat }
                        .getOrNull()
                        ?.takeIf { it.isFinite() && it >= 0f }
                }
                screens[definition.name] = ManagedScreen(definition, radius)
            }
            PremiereCore.LOGGER.info("Loaded {} screen(s)", screens.size)
        } catch (e: Exception) {
            PremiereCore.LOGGER.error("Failed to load {}; starting with no screens", path, e)
        }
    }

    private fun save(): Boolean {
        val platform = this.platform ?: return false
        val array = JsonArray()
        for (screen in screens.values) {
            val d = screen.definition
            array.add(JsonObject().apply {
                addProperty("name", d.name)
                addProperty("dimension", d.dimension)
                addProperty("x", d.origin.x)
                addProperty("y", d.origin.y)
                addProperty("z", d.origin.z)
                addProperty("width", d.width)
                addProperty("height", d.height)
                addProperty("facing", d.facing.serializedName)
                screen.audioFullVolumeRadiusOverride?.let {
                    addProperty("audio_full_volume_radius", it)
                }
            })
        }
        val root = JsonObject().apply { add("screens", array) }
        return JsonConfig.writeStringAtomic(
            platform.screensFile,
            GsonBuilder().setPrettyPrinting().create().toJson(root),
        )
    }
}
