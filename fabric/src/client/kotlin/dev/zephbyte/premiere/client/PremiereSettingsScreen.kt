package dev.zephbyte.premiere.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * In-game settings, SVC-style, so players never edit premiere-client.json by
 * hand. Opened with its keybind (see PremiereClient); subtitles have their
 * own sub-screen with a live preview.
 */
class PremiereSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Premiere Settings")) {

    override fun init() {
        val centerX = width / 2
        val controlWidth = (width - 40).coerceIn(180, 220)
        val left = centerX - controlWidth / 2
        var y = (height / 2 - 72).coerceAtLeast(34)

        addRenderableWidget(
            Button.builder(Component.literal("Subtitles…")) {
                Minecraft.getInstance().gui.setScreen(SubtitleSettingsScreen(this))
            }.bounds(left, y, controlWidth, 20).build()
        )
        y += 24

        addRenderableWidget(
            SettingsSlider(
                left, y, controlWidth, "A/V Sync",
                -PremiereClientConfig.MAX_AV_SYNC_MS.toDouble(),
                PremiereClientConfig.MAX_AV_SYNC_MS.toDouble(),
                PremiereClientConfig.avSyncMs.toDouble(),
                { "%+d ms".format(snap(it)) },
            ) { PremiereClientConfig.updateAvSyncMs(snap(it)) }
        )
        y += 24

        addRenderableWidget(
            SettingsSlider(
                left, y, controlWidth, "Movie Volume",
                0.0, 1.0, PremiereClientConfig.movieVolume.toDouble(),
                { "%d%%".format((it * 100).toInt()) },
            ) { PremiereClientConfig.updateMovieVolume(it.toFloat()) }
        )
        y += 24

        addRenderableWidget(
            CycleButton.onOffBuilder(PremiereClientConfig.letterboxBlack)
                .create(left, y, controlWidth, 20, Component.literal("Black Letterbox")) { _, value ->
                    PremiereClientConfig.updateLetterboxBlack(value)
                }
        )
        y += 24

        addRenderableWidget(
            CycleButton.onOffBuilder(PremiereClientConfig.hideCrosshairAtScreen)
                .create(left, y, controlWidth, 20, Component.literal("Hide Crosshair on Movies")) { _, value ->
                    PremiereClientConfig.updateHideCrosshair(value)
                }
        )
        y += 28

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(left, y, controlWidth, 20)
                .build()
        )
    }

    private fun snap(value: Double): Int = ((value / 25.0).roundToInt() * 25).coerceIn(
        -PremiereClientConfig.MAX_AV_SYNC_MS,
        PremiereClientConfig.MAX_AV_SYNC_MS,
    )

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.centeredText(font, title, width / 2, (height / 2 - 96).coerceAtLeast(10), 0xFFFFFFFF.toInt())
    }

    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(parent)
    }
}
