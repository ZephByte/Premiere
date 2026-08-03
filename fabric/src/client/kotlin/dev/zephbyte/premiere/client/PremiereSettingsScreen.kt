package dev.zephbyte.premiere.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * In-game settings, SVC-style, so players never edit premiere-client.json by
 * hand. Opened with its keybind (see PremiereClient); subtitles have their
 * own sub-screen with a live preview.
 */
class PremiereSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Premiere Settings")) {

    private var avSyncBox: EditBox? = null
    private var avSyncSlider: SettingsSlider? = null

    override fun init() {
        val centerX = width / 2
        var y = height / 2 - 88

        addRenderableWidget(
            Button.builder(Component.literal("Subtitle Settings...")) {
                Minecraft.getInstance().gui.setScreen(SubtitleSettingsScreen(this))
            }.bounds(centerX - 100, y, 200, 20).build()
        )
        y += 32

        // A/V trim: slider for feel, box for exact values. Applies live, so
        // adjust while a film is playing until lips match voices.
        avSyncSlider = addRenderableWidget(
            SettingsSlider(
                centerX - 100, y, 144, "A/V Sync",
                -PremiereClientConfig.MAX_AV_SYNC_MS.toDouble(),
                PremiereClientConfig.MAX_AV_SYNC_MS.toDouble(),
                PremiereClientConfig.avSyncMs.toDouble(),
                { "%+d".format(snap(it)) },
            ) {
                PremiereClientConfig.updateAvSyncMs(snap(it))
                avSyncBox?.setValue(PremiereClientConfig.avSyncMs.toString())
            }
        )
        val box = EditBox(font, centerX + 48, y, 52, 20, Component.literal("A/V Sync ms"))
        box.setValue(PremiereClientConfig.avSyncMs.toString())
        box.setResponder { text ->
            text.toIntOrNull()?.let { typed ->
                if (typed in -PremiereClientConfig.MAX_AV_SYNC_MS..PremiereClientConfig.MAX_AV_SYNC_MS) {
                    PremiereClientConfig.updateAvSyncMs(typed)
                    avSyncSlider?.setMappedValue(PremiereClientConfig.avSyncMs.toDouble())
                }
            }
        }
        addRenderableWidget(box)
        avSyncBox = box
        y += 26

        addRenderableWidget(
            SettingsSlider(
                centerX - 100, y, 200, "Movie Volume",
                0.0, 1.0, PremiereClientConfig.movieVolume.toDouble(),
                { "%d%%".format((it * 100).toInt()) },
            ) { PremiereClientConfig.updateMovieVolume(it.toFloat()) }
        )
        y += 26

        addRenderableWidget(
            CycleButton.builder<Boolean>(
                { on -> Component.literal(if (on) "Black" else "Transparent") },
                PremiereClientConfig.letterboxBlack,
            ).withValues(true, false)
                .create(centerX - 100, y, 200, 20, Component.literal("Letterbox Bars")) { _, value ->
                    PremiereClientConfig.updateLetterboxBlack(value)
                }
        )
        y += 26

        addRenderableWidget(
            CycleButton.onOffBuilder(PremiereClientConfig.hideCrosshairAtScreen)
                .create(centerX - 100, y, 200, 20, Component.literal("Hide Crosshair At Screen")) { _, value ->
                    PremiereClientConfig.updateHideCrosshair(value)
                }
        )
        y += 30

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(centerX - 100, y, 200, 20)
                .build()
        )
    }

    private fun snap(value: Double): Int = ((value / 25).toInt() * 25).coerceIn(
        -PremiereClientConfig.MAX_AV_SYNC_MS,
        PremiereClientConfig.MAX_AV_SYNC_MS,
    )

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.text(font, title.string, (width - font.width(title.string)) / 2, height / 2 - 110, 0xFFFFFFFF.toInt(), true)
        graphics.text(
            font,
            "Voices after lips: increase. Voices before lips: decrease.",
            width / 2 - 100,
            height / 2 - 30,
            0xFFA0A0A0.toInt(),
            true,
        )
    }

    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(parent)
    }
}
