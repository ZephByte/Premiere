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
import dev.zephbyte.premiere.client.video.ScreenGaze
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements

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
        // Melt the crosshair out of the picture: wrap the vanilla element and
        // skip it while the player is looking at a screen with a film up.
        HudElementRegistry.replaceElement(
            VanillaHudElements.CROSSHAIR
        ) { original ->
            HudElement { graphics, delta ->
                if (!PremiereClientConfig.hideCrosshairAtScreen ||
                    !ScreenGaze.lookingAtActiveScreen()
                ) {
                    original.extractRenderState(graphics, delta)
                }
            }
        }
    }

    private fun render(graphics: GuiGraphicsExtractor, @Suppress("unused") delta: DeltaTracker) {
        if (!PremiereClientConfig.subtitlesEnabled) return
        val minecraft = Minecraft.getInstance()
        // The subtitle settings screen shows its own preview cue; real cues
        // underneath it would just be clutter while tuning size/position.
        if (minecraft.gui.screen() is dev.zephbyte.premiere.client.SubtitleSettingsScreen) return
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

        drawCue(graphics, cue.lines)
    }

    /**
     * Draws cue lines at the configured position and scale. The pose scale
     * makes subtitle size independent of GUI scale — cinema captions
     * shouldn't shrink because someone likes a compact hotbar. Shared with
     * the settings screen's live preview.
     */
    fun drawCue(graphics: GuiGraphicsExtractor, lines: List<String>) {
        val font = Minecraft.getInstance().font
        val scale = PremiereClientConfig.subtitleScale
        val maxWidth = (graphics.guiWidth() / scale * 0.75f).toInt().coerceAtLeast(50)
        val wrapped = lines.flatMap { font.split(Component.literal(it), maxWidth) }
        if (wrapped.isEmpty()) return

        val pose = graphics.pose()
        pose.pushMatrix()
        val anchorY = graphics.guiHeight() * (1f - PremiereClientConfig.subtitleBottomPercent / 100f)
        pose.translate(graphics.guiWidth() / 2f, anchorY)
        pose.scale(scale)

        val lineHeight = font.lineHeight + 2
        var y = -(wrapped.size * lineHeight) // the block sits above the anchor
        for (line in wrapped) {
            val width = font.width(line)
            val x = -width / 2
            graphics.fill(x - 3, y - 2, x + width + 3, y + font.lineHeight + 1, 0x90000000.toInt())
            graphics.text(font, line, x, y, 0xFFFFFFFF.toInt(), true)
            y += lineHeight
        }
        pose.popMatrix()
    }
}
