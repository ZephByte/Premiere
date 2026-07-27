package dev.zephbyte.premiere.client.subtitles

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.client.ClientScreens
import dev.zephbyte.premiere.client.PremiereClientConfig
import dev.zephbyte.premiere.screen.PlayState
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Cinema-style subtitles at the bottom of the HUD, driven by the same master
 * clock as the picture. Shown when subtitles are toggled on and the player is
 * near a playing screen whose film has a sidecar .srt.
 */
object SubtitleHud {

    /** How close (blocks) to a screen's face you must be to get subtitles. */
    private const val RANGE = 64.0
    private const val RANGE_SQ = RANGE * RANGE

    fun register() {
        HudElementRegistry.addLast(Premiere.id("subtitles"), ::render)
    }

    private fun render(graphics: GuiGraphicsExtractor, @Suppress("unused") delta: DeltaTracker) {
        if (!PremiereClientConfig.subtitlesEnabled) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val dimension = level.dimension().identifier().toString()

        // Nearest playing/paused screen with subtitles (sidecar .srt or a text
        // track embedded in the film itself), within range.
        var best: ClientScreens.ActiveScreen? = null
        var bestDistance = RANGE_SQ
        for (active in ClientScreens.renderable()) {
            val videoPlayer = active.player ?: continue
            if (active.subtitleUrl.isEmpty() && !videoPlayer.hasEmbeddedSubtitles()) continue
            if (active.state != PlayState.PLAYING && active.state != PlayState.PAUSED) continue
            val definition = active.definition
            if (definition.dimension != dimension) continue
            val distance = player.position().distanceToSqr(definition.faceCenter())
            if (distance < bestDistance) {
                bestDistance = distance
                best = active
            }
        }
        val screen = best ?: return
        val videoPlayer = screen.player ?: return
        val position = videoPlayer.currentMediaMs()
        // A staff-provided sidecar .srt wins over the film's embedded track.
        val cue = if (screen.subtitleUrl.isNotEmpty()) {
            val cues = SubtitleStore.cuesFor(screen.subtitleUrl) ?: return // still fetching
            SubtitleStore.activeCue(cues, position)
        } else {
            videoPlayer.activeEmbeddedCue(position)
        } ?: return

        val font = minecraft.font
        val maxWidth = graphics.guiWidth() * 3 / 4
        val wrapped = cue.lines.flatMap { font.split(Component.literal(it), maxWidth) }
        if (wrapped.isEmpty()) return

        val lineHeight = font.lineHeight + 2
        var y = graphics.guiHeight() - 64 - wrapped.size * lineHeight
        for (line in wrapped) {
            val width = font.width(line)
            val x = (graphics.guiWidth() - width) / 2
            graphics.fill(x - 3, y - 2, x + width + 3, y + font.lineHeight + 1, 0x90000000.toInt())
            graphics.text(font, line, x, y, 0xFFFFFFFF.toInt(), true)
            y += lineHeight
        }
    }
}
