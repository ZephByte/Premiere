package dev.zephbyte.premiere.upload

import dev.zephbyte.premiere.PremiereCore
import dev.zephbyte.premiere.PremiereConfig

/**
 * The bucket, viewed as a named movie library. Staff play by name
 * (`/movienight play theater intro_joke`), never by URL; the key list is
 * cached briefly so tab-completion stays off the network and off the server
 * thread.
 */
object MovieLibrary {

    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedKeys: List<String> = emptyList()

    @Volatile
    private var fetchedAt = 0L

    @Volatile
    private var refreshing = false

    private val SUBTITLE_EXTENSIONS = setOf("srt")

    fun isSubtitle(key: String): Boolean =
        key.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS

    /** Display names for tab-completion: video keys minus their extension. */
    fun suggestions(): List<String> {
        refreshSoon()
        return cachedKeys.filterNot(::isSubtitle).map(::displayName).distinct()
    }

    /**
     * Resolves a staff-typed name to a video object key. Blocking (may hit
     * R2); call off the server thread. Matches the exact key first, then the
     * key's extension-less name, case-insensitively.
     */
    fun resolve(name: String): String? {
        val keys = freshKeys().filterNot(::isSubtitle)
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        return keys.firstOrNull { displayName(it).equals(name, ignoreCase = true) }
    }

    /** Sidecar subtitles: an .srt sharing the video's extension-less name. */
    fun subtitleKeyFor(videoKey: String): String? {
        val base = displayName(videoKey)
        return freshKeys().firstOrNull { isSubtitle(it) && displayName(it).equals(base, ignoreCase = true) }
    }

    fun displayName(key: String): String = key.substringBeforeLast('.')

    /**
     * Library listing for /pm movies: video display names, with "(cc)"
     * appended when a sidecar subtitle shares the name. [keys] is a full
     * bucket listing (call off the server thread).
     */
    fun displayNamesWithCc(keys: List<String>): List<String> {
        val subtitleNames = keys.filter(::isSubtitle)
            .map { displayName(it).lowercase() }
            .toSet()
        return keys.filterNot(::isSubtitle).map {
            val name = displayName(it)
            if (name.lowercase() in subtitleNames) "$name (cc)" else name
        }
    }

    private fun freshKeys(): List<String> {
        if (System.currentTimeMillis() - fetchedAt >= CACHE_TTL_MS) {
            try {
                cachedKeys = R2Storage.listKeys()
                fetchedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                PremiereCore.LOGGER.warn("Could not list movie library: {}", e.message)
            }
        }
        return cachedKeys
    }

    /** Non-blocking cache refresh for suggestion providers. */
    private fun refreshSoon() {
        if (!PremiereConfig.uploadConfigured) return
        if (System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS || refreshing) return
        refreshing = true
        Thread.startVirtualThread {
            try {
                freshKeys()
            } finally {
                refreshing = false
            }
        }
    }
}
