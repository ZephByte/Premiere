package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.util.JsonConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/**
 * Client-side preferences, in config/premiere-client.json. Edited through the
 * in-game settings screen; every update saves immediately.
 */
object PremiereClientConfig {

    @Volatile
    var subtitlesEnabled: Boolean = false
        private set

    /**
     * Preferred language for subtitle tracks embedded in the film, as an
     * ISO 639 code the way releases tag them ("eng", "jpn", "spa", ...).
     */
    @Volatile
    var subtitleLanguage: String = "eng"
        private set

    /** Hide the crosshair while looking at a screen with a film up. */
    @Volatile
    var hideCrosshairAtScreen: Boolean = true
        private set

    /** Subtitle text scale, independent of GUI scale. */
    @Volatile
    var subtitleScale: Float = 1.0f
        private set

    /** Where subtitles sit: percent of screen height up from the bottom. */
    @Volatile
    var subtitleBottomPercent: Int = 12
        private set

    /**
     * Per-player A/V trim: how much this client delays its video (and
     * subtitles) to land on the audio actually reaching its ears. The delay
     * between the server sending audio and a player hearing it is
     * per-connection (network + SVC's client buffer), so no server-side
     * constant can be right for everyone — this is the knob that can.
     */
    @Volatile
    var avSyncMs: Int = 0
        private set

    /** Fill unused screen area behind the picture with black (vs transparent). */
    @Volatile
    var letterboxBlack: Boolean = true
        private set

    fun toggleSubtitles(): Boolean {
        subtitlesEnabled = !subtitlesEnabled
        save()
        return subtitlesEnabled
    }

    fun updateSubtitlesEnabled(value: Boolean) {
        subtitlesEnabled = value
        save()
    }

    fun updateSubtitleLanguage(value: String) {
        val cleaned = value.lowercase().trim()
        if (cleaned.isNotEmpty() && cleaned != subtitleLanguage) {
            subtitleLanguage = cleaned
            save()
        }
    }

    fun updateHideCrosshair(value: Boolean) {
        hideCrosshairAtScreen = value
        save()
    }

    fun updateSubtitleScale(value: Float) {
        subtitleScale = value.coerceIn(0.5f, 2.5f)
        save()
    }

    fun updateSubtitleBottomPercent(value: Int) {
        subtitleBottomPercent = value.coerceIn(2, 40)
        save()
    }

    fun updateAvSyncMs(value: Int) {
        avSyncMs = value.coerceIn(-1000, 1000)
        save()
    }

    fun updateLetterboxBlack(value: Boolean) {
        letterboxBlack = value
        save()
    }

    private fun path(): Path = FabricLoader.getInstance().configDir.resolve("premiere-client.json")

    fun load() {
        JsonConfig.read(path())?.let { o ->
            o["subtitles"]?.let { subtitlesEnabled = it.asBoolean }
            o["subtitle_language"]?.let { subtitleLanguage = it.asString.lowercase() }
            o["hide_crosshair_at_screen"]?.let { hideCrosshairAtScreen = it.asBoolean }
            o["subtitle_scale"]?.let { subtitleScale = it.asFloat.coerceIn(0.5f, 2.5f) }
            o["subtitle_bottom_percent"]?.let { subtitleBottomPercent = it.asInt.coerceIn(2, 40) }
            o["av_sync_ms"]?.let { avSyncMs = it.asInt.coerceIn(-1000, 1000) }
            o["letterbox_black"]?.let { letterboxBlack = it.asBoolean }
        }
        save() // keep the file discoverable as new keys appear
    }

    private fun save() = JsonConfig.write(path()) {
        addProperty("subtitles", subtitlesEnabled)
        addProperty("subtitle_language", subtitleLanguage)
        addProperty("hide_crosshair_at_screen", hideCrosshairAtScreen)
        addProperty("subtitle_scale", subtitleScale)
        addProperty("subtitle_bottom_percent", subtitleBottomPercent)
        addProperty("av_sync_ms", avSyncMs)
        addProperty("letterbox_black", letterboxBlack)
    }
}
