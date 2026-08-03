package dev.zephbyte.premiere.command

import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.net.URI

/** Consistent, compact staff-facing command presentation. */
internal object CommandFeedback {

    private fun prefix(): MutableComponent = Component.literal("Premiere ")
        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD)

    fun success(text: String): MutableComponent = prefix().copy()
        .append(Component.literal("✓ ").withStyle(ChatFormatting.GREEN))
        .append(Component.literal(text).withStyle(ChatFormatting.GRAY))

    fun info(text: String): MutableComponent = prefix().copy()
        .append(Component.literal("• ").withStyle(ChatFormatting.AQUA))
        .append(Component.literal(text).withStyle(ChatFormatting.GRAY))

    fun error(text: String): MutableComponent = prefix().copy()
        .append(Component.literal("✕ ").withStyle(ChatFormatting.RED))
        .append(Component.literal(text).withStyle(ChatFormatting.RED))

    fun value(text: String): MutableComponent = Component.literal(text).withStyle(ChatFormatting.WHITE)

    fun muted(text: String): MutableComponent = Component.literal(text).withStyle(ChatFormatting.DARK_GRAY)

    fun command(label: String, command: String): MutableComponent = Component.literal(label).withStyle { style ->
        style.withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(ClickEvent.SuggestCommand(command))
    }

    fun url(label: String, url: String): MutableComponent = Component.literal(label).withStyle { style ->
        runCatching { style.withClickEvent(ClickEvent.OpenUrl(URI(url))) }
            .getOrDefault(style)
            .withColor(ChatFormatting.AQUA)
            .withBold(true)
            .withUnderlined(true)
    }

    fun sendSuccess(source: CommandSourceStack, text: String, broadcast: Boolean = true) {
        source.sendSuccess({ success(text) }, broadcast)
    }

    fun sendInfo(source: CommandSourceStack, text: String) {
        source.sendSuccess({ info(text) }, false)
    }

    fun sendError(source: CommandSourceStack, text: String) {
        source.sendFailure(error(text))
    }
}
