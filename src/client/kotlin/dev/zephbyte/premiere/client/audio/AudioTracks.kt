package dev.zephbyte.premiere.client.audio

import dev.zephbyte.premiere.Premiere
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber

/**
 * Audio track selection for multi-audio films (dual-audio anime etc.).
 * The wanted stream is only knowable after the container is open, and
 * stream selection is fixed at open time, so a language match costs one
 * reopen. ffmpeg's own default pick ignores the container's default flag,
 * so an explicit preference is the only reliable behavior.
 */
object AudioTracks {

    /** Reopens [grabber]'s URL pinned to the best [language] track, if any. */
    fun reopenForLanguage(grabber: FFmpegFrameGrabber, url: String, language: String, configure: (FFmpegFrameGrabber) -> Unit): FFmpegFrameGrabber {
        if (language.isBlank()) return grabber
        val wanted = pick(grabber, language)
        if (wanted < 0) {
            Premiere.LOGGER.info("No '{}' audio track; using the file's default", language)
            return grabber
        }
        runCatching { grabber.stop(); grabber.release() }
        return FFmpegFrameGrabber(url).also {
            configure(it)
            it.audioStream = wanted // absolute stream index
            it.start()
        }
    }

    private fun pick(grabber: FFmpegFrameGrabber, language: String): Int {
        val context = grabber.formatContext ?: return -1
        var best = -1
        var bestScore = 0
        for (i in 0 until context.nb_streams()) {
            val stream = context.streams(i) ?: continue
            val par = stream.codecpar() ?: continue
            if (par.codec_type() != avutil.AVMEDIA_TYPE_AUDIO) continue
            val streamLanguage = avutil.av_dict_get(stream.metadata(), "language", null, 0)
                ?.value()?.string?.lowercase() ?: ""
            if (!streamLanguage.startsWith(language.lowercase())) continue
            var score = 10
            if (stream.disposition() and avformat.AV_DISPOSITION_DEFAULT != 0) score += 5
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }
}
