package dev.zephbyte.premiere.util

object Times {

    /** Accepts h:mm:ss, m:ss, plain seconds, and relative +30 / -1:30 forms. */
    fun parseMs(input: String, currentMs: Long): Long? {
        val relative = input.startsWith("+") || input.startsWith("-")
        val negative = input.startsWith("-")
        val body = input.trimStart('+', '-')
        val parts = body.split(":")
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        var seconds = 0L
        for (part in parts) seconds = seconds * 60 + part.toLong()
        val deltaMs = seconds * 1000
        return if (relative) currentMs + (if (negative) -deltaMs else deltaMs) else deltaMs
    }

    fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
