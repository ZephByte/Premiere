package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.client.subtitles.SubtitleHud
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Subtitle preferences with a live preview through the real pipeline. While
 * the size or height slider is being dragged, everything else — widgets,
 * blur, darkening, even the real subtitles — gets out of the way so what's
 * on screen is exactly what films will look like.
 */
class SubtitleSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Subtitle Settings")) {

    private var languageBox: EditBox? = null
    private var sizeSlider: SettingsSlider? = null
    private var heightSlider: SettingsSlider? = null

    /** True while size/height is being dragged: render only the preview. */
    private fun adjusting(): Boolean =
        isDragging && (focused === sizeSlider || focused === heightSlider)

    override fun init() {
        val centerX = width / 2
        var y = height / 2 - 76

        addRenderableWidget(
            CycleButton.onOffBuilder(PremiereClientConfig.subtitlesEnabled)
                .create(centerX - 100, y, 200, 20, Component.literal("Movie Subtitles")) { _, value ->
                    PremiereClientConfig.updateSubtitlesEnabled(value)
                }
        )
        y += 32

        val box = EditBox(font, centerX - 100, y, 200, 20, Component.literal("Subtitle Language"))
        box.setValue(PremiereClientConfig.subtitleLanguage)
        addRenderableWidget(box)
        languageBox = box
        y += 26

        sizeSlider = addRenderableWidget(
            SettingsSlider(
                centerX - 100, y, 200, "Size",
                0.5, 2.5, PremiereClientConfig.subtitleScale.toDouble(),
                { "%d%%".format((it * 100).toInt()) },
            ) { PremiereClientConfig.updateSubtitleScale(it.toFloat()) }
        )
        y += 26

        heightSlider = addRenderableWidget(
            SettingsSlider(
                centerX - 100, y, 200, "Height",
                2.0, 40.0, PremiereClientConfig.subtitleBottomPercent.toDouble(),
                { "%d%% up".format(it.toInt()) },
            ) { PremiereClientConfig.updateSubtitleBottomPercent(it.toInt()) }
        )
        y += 30

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(centerX - 100, y, 200, 20)
                .build()
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (adjusting()) {
            // No super call: no blur, no darkening, no widgets — a clean look
            // at the world with only the preview cue on it.
            SubtitleHud.drawCue(graphics, PREVIEW_LINES)
            val hint = "Release to finish adjusting"
            graphics.text(font, hint, (width - font.width(hint)) / 2, 8, 0xFFA0A0A6.toInt(), true)
            return
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.text(font, title.string, (width - font.width(title.string)) / 2, height / 2 - 98, 0xFFFFFFFF.toInt(), true)
        graphics.text(
            font,
            "Language as releases tag it: eng, jpn, spa, ...",
            width / 2 - 100,
            height / 2 - 42,
            0xFFA0A0A0.toInt(),
            true,
        )
        SubtitleHud.drawCue(graphics, PREVIEW_LINES)
    }

    override fun onClose() {
        languageBox?.let { PremiereClientConfig.updateSubtitleLanguage(it.value) }
        Minecraft.getInstance().gui.setScreen(parent)
    }

    companion object {
        private val PREVIEW_LINES = listOf("Subtitles will look like this.", "Size and height preview")
    }
}
