package dev.zephbyte.premiere.upload

import dev.zephbyte.premiere.Premiere
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

    /** Display names for tab-completion: keys minus their extension. */
    fun suggestions(): List<String> {
        refreshSoon()
        return cachedKeys.map(::displayName).distinct()
    }

    /**
     * Resolves a staff-typed name to an object key. Blocking (may hit R2);
     * call off the server thread. Matches the exact key first, then the key's
     * extension-less name, case-insensitively.
     */
    fun resolve(name: String): String? {
        val keys = freshKeys()
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        return keys.firstOrNull { displayName(it).equals(name, ignoreCase = true) }
    }

    fun displayName(key: String): String = key.substringBeforeLast('.')

    private fun freshKeys(): List<String> {
        if (System.currentTimeMillis() - fetchedAt >= CACHE_TTL_MS) {
            try {
                cachedKeys = R2Storage.listKeys()
                fetchedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Premiere.LOGGER.warn("Could not list movie library: {}", e.message)
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
