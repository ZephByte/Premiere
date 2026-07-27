package dev.zephbyte.premiere.client.subtitles

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.util.MediaUrls
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class SubtitleCue(val startMs: Long, val endMs: Long, val lines: List<String>)

/**
 * Fetches and caches sidecar .srt files. A subtitle file for a feature film
 * is a few hundred KB, so the whole thing is downloaded once and cues are
 * looked up against the master clock every frame.
 */
object SubtitleStore {

    private const val MAX_BYTES = 2 * 1024 * 1024

    private val cache = ConcurrentHashMap<String, List<SubtitleCue>>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** Cues for [url], or null while not yet fetched (kicks off the fetch). */
    fun cuesFor(url: String): List<SubtitleCue>? {
        cache[url]?.let { return it }
        if (fetching.add(url)) {
            Thread.startVirtualThread { fetch(url) }
        }
        return null
    }

    fun clear() {
        cache.clear()
        fetching.clear()
    }

    private fun fetch(url: String) {
        try {
            MediaUrls.validateResolved(url)?.let { error ->
                Premiere.LOGGER.warn("Rejecting subtitle URL: {}", error)
                cache[url] = emptyList()
                return
            }
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299 || response.body().size > MAX_BYTES) {
                Premiere.LOGGER.warn(
                    "Subtitle fetch failed: HTTP {} ({} bytes)", response.statusCode(), response.body().size
                )
                cache[url] = emptyList()
                return
            }
            val cues = parseSrt(response.body().decodeToString())
            Premiere.LOGGER.info("Loaded {} subtitle cues", cues.size)
            cache[url] = cues
        } catch (e: Exception) {
            Premiere.LOGGER.warn("Subtitle fetch failed for {}: {}", url, e.message)
            cache[url] = emptyList()
        } finally {
            fetching.remove(url)
        }
    }

    private val TIME_LINE = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )
    private val TAGS = Regex("<[^>]*>|\\{[^}]*\\}")

    /** Tolerant SRT parser (also accepts '.' millisecond separators). */
    fun parseSrt(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val blocks = text.removePrefix("﻿").replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.trim().lines()
            val timeIndex = lines.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val match = TIME_LINE.find(lines[timeIndex]) ?: continue
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured
            fun ms(h: String, m: String, s: String, ms: String) =
                h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
            val textLines = lines.drop(timeIndex + 1)
                .map { TAGS.replace(it, "").trim() }
                .filter { it.isNotEmpty() }
            if (textLines.isEmpty()) continue
            cues.add(SubtitleCue(ms(h1, m1, s1, ms1), ms(h2, m2, s2, ms2), textLines))
        }
        cues.sortBy { it.startMs }
        return cues
    }

    /** Binary search for the cue active at [positionMs], if any. */
    fun activeCue(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }
}
