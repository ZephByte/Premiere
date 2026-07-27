package dev.zephbyte.premiere.upload

import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.util.MediaUrls
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Turns what staff typed — a library name or a pasted URL — into a playable
 * (url, label, subtitleUrl) triple. Blocking (DNS, bucket listing, signing);
 * call off the server thread. Shared by the command and the dashboard.
 */
object MediaResolver {

    data class Resolved(val url: String, val label: String, val subtitleUrl: String)

    class ResolveException(message: String) : Exception(message)

    @Throws(ResolveException::class)
    fun resolve(input: String): Resolved {
        val isUrl = input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        if (isUrl) {
            val error = MediaUrls.validate(input) ?: MediaUrls.validateResolved(input)
            if (error != null) throw ResolveException(error)
            return Resolved(input, input, probeSidecarSubtitles(input))
        }
        if (!PremiereConfig.uploadConfigured) {
            throw ResolveException("No movie library configured (r2_* settings in config/premiere.json); paste a URL instead.")
        }
        val key = try {
            MovieLibrary.resolve(input)
        } catch (e: Exception) {
            throw ResolveException("Could not reach the movie library: ${e.message}")
        } ?: throw ResolveException("No movie named '$input'. See /pm movies, or upload with /pm upload.")
        val subtitleUrl = MovieLibrary.subtitleKeyFor(key)?.let { R2Storage.presignGet(it) } ?: ""
        return Resolved(R2Storage.presignGet(key), MovieLibrary.displayName(key), subtitleUrl)
    }

    /** For pasted URLs: check whether `<url minus extension>.srt` exists. */
    private fun probeSidecarSubtitles(videoUrl: String): String {
        val candidate = videoUrl.substringBeforeLast('?').substringBeforeLast('.') + ".srt"
        if (MediaUrls.validate(candidate) != null) return ""
        return try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val request = HttpRequest.newBuilder(URI(candidate))
                .timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status in 200..299) candidate else ""
        } catch (e: Exception) {
            ""
        }
    }
}
