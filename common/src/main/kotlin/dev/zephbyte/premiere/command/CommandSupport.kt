package dev.zephbyte.premiere.command

import java.util.concurrent.ConcurrentHashMap

/**
 * Destructive commands take the same command twice within [windowMs] to
 * confirm (e.g. redefining an existing screen). Keyed by sender name so
 * console and each player confirm independently.
 */
class ConfirmTracker(private val windowMs: Long = 30_000L) {
    private val pending = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * True when [sender] already asked for [name] within the window (and the
     * pending entry is consumed); false records the request and means "ask
     * the user to repeat it".
     */
    fun confirm(sender: String, name: String): Boolean {
        val previous = pending[sender]
        val confirmed = previous != null && previous.first == name &&
            System.currentTimeMillis() - previous.second < windowMs
        if (!confirmed) {
            pending[sender] = name to System.currentTimeMillis()
            return false
        }
        pending.remove(sender)
        return true
    }
}

/** Argument parsing shared by /pm play and /pm load on both platforms. */
object PlayArgs {
    private val AUDIO_FLAG = Regex("""\s+--audio\s+([A-Za-z]{2,3})\s*$""")

    /** Splits a trailing `--audio <lang>` off the movie argument. */
    fun parseAudioFlag(input: String): Pair<String, String> {
        val match = AUDIO_FLAG.find(input) ?: return input to ""
        return input.removeRange(match.range).trim() to match.groupValues[1].lowercase()
    }
}
