package dev.zephbyte.premiere.client

import dev.zephbyte.premiere.client.subtitles.SubtitleHud
import dev.zephbyte.premiere.client.subtitles.EmbeddedSubtitles
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.Locale

/** Clean subtitle controls with choices discovered from the current movie. */
class SubtitleSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Subtitles")) {

    private data class TrackChoice(val key: String, val label: String)
    private data class Popup(val x: Int, val y: Int, val width: Int, val rows: Int)

    private var sizeSlider: SettingsSlider? = null
    private var heightSlider: SettingsSlider? = null
    private var trackButton: Button? = null
    private var choices: List<TrackChoice> = emptyList()
    private var menuOpen = false
    private var menuOffset = 0

    /** While dragging, show the real placement without UI covering it. */
    private fun adjusting(): Boolean =
        isDragging && (focused === sizeSlider || focused === heightSlider)

    override fun init() {
        choices = discoverChoices()
        menuOpen = false
        menuOffset = 0

        val centerX = width / 2
        val controlWidth = (width - 40).coerceIn(180, 220)
        val left = centerX - controlWidth / 2
        var y = (height / 2 - 60).coerceAtLeast(34)

        addRenderableWidget(
            CycleButton.onOffBuilder(PremiereClientConfig.subtitlesEnabled)
                .create(left, y, controlWidth, 20, Component.literal("Movie Subtitles")) { _, value ->
                    PremiereClientConfig.updateSubtitlesEnabled(value)
                }
        )
        y += 24

        val selector = Button.builder(Component.literal(trackButtonText())) {
            if (choices.size > 1) menuOpen = !menuOpen
        }.bounds(left, y, controlWidth, 20).build()
        selector.active = choices.size > 1
        trackButton = addRenderableWidget(selector)
        y += 24

        sizeSlider = addRenderableWidget(
            SettingsSlider(
                left, y, controlWidth, "Size",
                0.5, 2.5, PremiereClientConfig.subtitleScale.toDouble(),
                { "%d%%".format((it * 100).toInt()) },
            ) { PremiereClientConfig.updateSubtitleScale(it.toFloat()) }
        )
        y += 24

        heightSlider = addRenderableWidget(
            SettingsSlider(
                left, y, controlWidth, "Position",
                2.0, 40.0, PremiereClientConfig.subtitleBottomPercent.toDouble(),
                { "%d%%".format(it.toInt()) },
            ) { PremiereClientConfig.updateSubtitleBottomPercent(it.toInt()) }
        )
        y += 28

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(left, y, controlWidth, 20)
                .build()
        )
    }

    override fun tick() {
        super.tick()
        if (discoverChoices() != choices) rebuildWidgets()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (adjusting()) {
            SubtitleHud.drawCue(graphics, PREVIEW_LINES)
            return
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.centeredText(font, title, width / 2, (height / 2 - 84).coerceAtLeast(10), 0xFFFFFFFF.toInt())
        if (menuOpen) drawMenu(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (menuOpen) {
            val selector = trackButton
            if (selector != null && selector.isMouseOver(event.x(), event.y())) {
                return super.mouseClicked(event, doubleClick)
            }

            val popup = popup()
            if (event.button() == 0 &&
                event.x() >= popup.x && event.x() < popup.x + popup.width &&
                event.y() >= popup.y && event.y() < popup.y + popup.rows * ROW_HEIGHT
            ) {
                val row = ((event.y() - popup.y) / ROW_HEIGHT).toInt()
                choices.getOrNull(menuOffset + row)?.let(::select)
                menuOpen = false
                return true
            }
            menuOpen = false
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        if (menuOpen) {
            val popup = popup()
            if (mouseX >= popup.x && mouseX < popup.x + popup.width &&
                mouseY >= popup.y && mouseY < popup.y + popup.rows * ROW_HEIGHT && vertical != 0.0
            ) {
                val direction = if (vertical > 0) -1 else 1
                menuOffset = (menuOffset + direction).coerceIn(0, (choices.size - popup.rows).coerceAtLeast(0))
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    private fun drawMenu(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val popup = popup()
        menuOffset = menuOffset.coerceIn(0, (choices.size - popup.rows).coerceAtLeast(0))
        graphics.nextStratum()
        graphics.fill(popup.x - 1, popup.y - 1, popup.x + popup.width + 1, popup.y + popup.rows * ROW_HEIGHT + 1, 0xFF09090C.toInt())

        choices.drop(menuOffset).take(popup.rows).forEachIndexed { row, choice ->
            val y = popup.y + row * ROW_HEIGHT
            val hovered = mouseX in popup.x until popup.x + popup.width && mouseY in y until y + ROW_HEIGHT
            val selected = choice.key == selectedChoice().key
            val color = when {
                hovered -> 0xFF4A4A58.toInt()
                selected -> 0xFF27495A.toInt()
                else -> 0xFF202027.toInt()
            }
            graphics.fill(popup.x, y, popup.x + popup.width, y + ROW_HEIGHT - 1, color)
            graphics.text(font, choice.label, popup.x + 6, y + (ROW_HEIGHT - font.lineHeight) / 2, 0xFFFFFFFF.toInt(), false)
        }

        if (choices.size > popup.rows) {
            val trackHeight = popup.rows * ROW_HEIGHT
            val thumbHeight = (trackHeight * popup.rows / choices.size).coerceAtLeast(6)
            val travel = trackHeight - thumbHeight
            val maxOffset = choices.size - popup.rows
            val thumbY = popup.y + if (maxOffset == 0) 0 else travel * menuOffset / maxOffset
            graphics.fill(popup.x + popup.width - 3, popup.y, popup.x + popup.width, popup.y + trackHeight, 0xFF111116.toInt())
            graphics.fill(popup.x + popup.width - 3, thumbY, popup.x + popup.width, thumbY + thumbHeight, 0xFF8A8A98.toInt())
        }
    }

    private fun popup(): Popup {
        val selector = trackButton ?: return Popup(0, 0, 0, 0)
        val desiredRows = choices.size.coerceAtMost(MAX_VISIBLE_ROWS)
        val below = height - selector.bottom - 6
        val above = selector.y - 6
        val openAbove = below < desiredRows * ROW_HEIGHT && above > below
        val available = if (openAbove) above else below
        val rows = desiredRows.coerceAtMost((available / ROW_HEIGHT).coerceAtLeast(1))
        val y = if (openAbove) selector.y - rows * ROW_HEIGHT - 2 else selector.bottom + 2
        return Popup(selector.x, y, selector.width, rows)
    }

    private fun select(choice: TrackChoice) {
        if (choice.key == SIDECAR || choice.key == NONE) return
        PremiereClientConfig.updateSubtitleLanguage(choice.key)
        trackButton?.message = Component.literal(trackButtonText())
    }

    private fun trackButtonText(): String {
        val choice = selectedChoice()
        val arrow = if (choices.size > 1) "  ▾" else ""
        return "Track: ${choice.label}$arrow"
    }

    private fun selectedChoice(): TrackChoice {
        if (choices.size <= 1) return choices.firstOrNull() ?: TrackChoice(NONE, "No tracks available")
        val preferred = PremiereClientConfig.subtitleLanguage
        return choices.firstOrNull { choice ->
            choice.key != AUTO &&
                EmbeddedSubtitles.languageMatches(choice.key, preferred)
        } ?: choices.first()
    }

    private fun discoverChoices(): List<TrackChoice> {
        val available = ClientScreens.nearestSubtitleAvailability()
            ?: return listOf(TrackChoice(NONE, "No tracks available"))
        if (available.sidecar) return listOf(TrackChoice(SIDECAR, "Provided with movie"))
        if (available.embeddedLanguages.isEmpty()) return listOf(TrackChoice(NONE, "No tracks available"))
        val languages = available.embeddedLanguages
            .map { TrackChoice(it, languageName(it)) }
            .sortedBy { it.label }
        return listOf(TrackChoice(AUTO, "Automatic")) + languages
    }

    private fun languageName(code: String): String {
        if (code == "und") return "Unlabeled"
        val base = code.substringBefore('-').substringBefore('_')
        val locale = if (base.length == 2) {
            Locale.forLanguageTag(base)
        } else {
            Locale.getISOLanguages().asSequence()
                .map(Locale::forLanguageTag)
                .firstOrNull { runCatching { it.getISO3Language().equals(base, ignoreCase = true) }.getOrDefault(false) }
        }
        val name = locale?.getDisplayLanguage(Locale.getDefault()).orEmpty()
        return name.takeIf { it.isNotBlank() && !it.equals(base, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            ?: code.uppercase(Locale.ROOT)
    }

    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(parent)
    }

    companion object {
        private const val AUTO = "auto"
        private const val SIDECAR = "sidecar"
        private const val NONE = "none"
        private const val ROW_HEIGHT = 20
        private const val MAX_VISIBLE_ROWS = 8
        private val PREVIEW_LINES = listOf("Subtitles will look like this.", "Size and position preview")
    }
}
