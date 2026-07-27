package dev.zephbyte.premiere.client

import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.network.chat.Component

/** A labeled slider over a [min]..[max] range, applying live as it moves. */
internal class SettingsSlider(
    x: Int,
    y: Int,
    width: Int,
    private val label: String,
    private val min: Double,
    private val max: Double,
    initial: Double,
    private val format: (Double) -> String,
    private val apply: (Double) -> Unit,
) : AbstractSliderButton(x, y, width, 20, Component.empty(), (initial - min) / (max - min)) {

    init {
        updateMessage()
    }

    private fun mapped(): Double = min + value * (max - min)

    override fun updateMessage() {
        message = Component.literal("$label: ${format(mapped())}")
    }

    override fun applyValue() {
        apply(mapped())
    }
}
