package dev.zephbyte.premiere.client.subtitles

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.charset.StandardCharsets

/**
 * Text subtitle tracks muxed into the film itself (S_TEXT tracks in MKV,
 * mov_text in MP4). The video grabber is asked to surface data packets, and
 * this turns them into cues — same connection as the picture, no sidecar
 * needed. Bitmap tracks (Blu-ray PGS, DVD) are images, not text, and are
 * deliberately ignored; those need OCR into an .srt.
 */
object EmbeddedSubtitles {

    class Track(
        val streamIndex: Int,
        val codecId: Int,
        val millisPerPts: Double,
        val language: String,
        val disposition: Int,
    )

    private val TAGS = Regex("<[^>]*>|\\{[^}]*\\}")

    private val TEXT_CODECS = setOf(
        avcodec.AV_CODEC_ID_SUBRIP,
        avcodec.AV_CODEC_ID_ASS,
        avcodec.AV_CODEC_ID_SSA,
        avcodec.AV_CODEC_ID_TEXT,
        avcodec.AV_CODEC_ID_WEBVTT,
        avcodec.AV_CODEC_ID_MOV_TEXT,
    )

    /** Every text-subtitle stream in the file (bitmap tracks are skipped). */
    fun tracks(grabber: FFmpegFrameGrabber): List<Track> {
        val context = grabber.formatContext ?: return emptyList()
        val found = ArrayList<Track>()
        for (i in 0 until context.nb_streams()) {
            val stream = context.streams(i) ?: continue
            val par = stream.codecpar() ?: continue
            if (par.codec_type() != avutil.AVMEDIA_TYPE_SUBTITLE) continue
            if (par.codec_id() !in TEXT_CODECS) continue
            val timeBase = stream.time_base()
            val language = org.bytedeco.ffmpeg.global.avutil
                .av_dict_get(stream.metadata(), "language", null, 0)
                ?.value()?.string?.lowercase() ?: ""
            found.add(
                Track(i, par.codec_id(), 1000.0 * timeBase.num() / timeBase.den(), language, stream.disposition())
            )
        }
        return found
    }

    /**
     * Best track for a language. Real releases carry many: a "forced" track
     * (foreign-language moments only — picking that looks like "subtitles
     * don't work"), SDH variants, and a dozen languages. Score rather than
     * take the first: prefer the requested language, shun forced, mildly
     * shun SDH. Re-run whenever the player changes language — cues for every
     * track are collected regardless, so switching is instant.
     */
    fun bestTrack(tracks: List<Track>, language: String): Track? {
        val preferred = language.lowercase()
        var best: Track? = null
        var bestScore = Int.MIN_VALUE
        for (track in tracks) {
            var score = 0
            if (track.language.isNotEmpty() &&
                (track.language.startsWith(preferred) || preferred.startsWith(track.language))
            ) score += 100
            if (track.disposition and org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_FORCED != 0) score -= 1000
            if (track.disposition and org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_HEARING_IMPAIRED != 0) score -= 10
            if (track.disposition and org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT != 0) score += 5
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }

    /** Converts one data frame from the grabber into a cue, or null. */
    fun parsePacket(frame: Frame, track: Track): SubtitleCue? {
        if (frame.streamIndex != track.streamIndex) return null
        val data = frame.data ?: return null
        val packet = frame.opaque as? AVPacket ?: return null
        if (packet.pts() == avutil.AV_NOPTS_VALUE) return null

        val bytes = ByteArray(data.remaining()).also { data.duplicate().get(it) }
        val raw = when (track.codecId) {
            // mov_text payload: 2-byte big-endian length, then UTF-8 text
            avcodec.AV_CODEC_ID_MOV_TEXT ->
                if (bytes.size <= 2) "" else String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_8)
            else -> String(bytes, StandardCharsets.UTF_8)
        }
        val text = when (track.codecId) {
            // ASS packet: "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text"
            avcodec.AV_CODEC_ID_ASS, avcodec.AV_CODEC_ID_SSA -> {
                val fields = raw.split(",", limit = 9)
                (fields.getOrNull(8) ?: raw).replace("\\N", "\n").replace("\\n", "\n")
            }
            else -> raw
        }
        val lines = text.split('\n')
            .map { TAGS.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val startMs = (packet.pts() * track.millisPerPts).toLong()
        var durationMs = (packet.duration() * track.millisPerPts).toLong()
        if (durationMs <= 0) {
            // No duration in the container: hold roughly reading speed.
            durationMs = (1500L + lines.sumOf { it.length } * 50L).coerceAtMost(7000L)
        }
        return SubtitleCue(startMs, startMs + durationMs, lines)
    }
}
