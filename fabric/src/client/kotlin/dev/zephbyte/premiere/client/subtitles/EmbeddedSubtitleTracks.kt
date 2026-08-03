package dev.zephbyte.premiere.client.subtitles

import dev.zephbyte.premiere.client.PremiereClientConfig
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * One film's embedded text subtitles: cues are collected for EVERY text
 * track as their packets stream in alongside the video, so switching
 * language in the settings screen takes effect instantly — no restart.
 *
 * Cue maps are keyed by start time: naturally sorted, and re-decoded packets
 * after a hard seek simply overwrite themselves. Written by the decode
 * thread, read by the HUD thread.
 */
class EmbeddedSubtitleTracks {

    @Volatile
    private var tracks: List<EmbeddedSubtitles.Track> = emptyList()
    private var trackByStream: Map<Int, EmbeddedSubtitles.Track> = emptyMap()
    private val cuesByStream = ConcurrentHashMap<Int, ConcurrentSkipListMap<Long, SubtitleCue>>()

    @Volatile
    private var selectedTrack: EmbeddedSubtitles.Track? = null

    @Volatile
    private var selectedForLanguage: String? = null

    /** Decode thread, once, right after the grabber opens. */
    fun discover(grabber: FFmpegFrameGrabber) {
        tracks = EmbeddedSubtitles.tracks(grabber)
        trackByStream = tracks.associateBy { it.streamIndex }
    }

    fun any(): Boolean = tracks.isNotEmpty()

    /** Languages actually advertised by this film's decodable text tracks. */
    fun availableLanguages(): List<String> = tracks
        .map { it.language.trim().lowercase().ifEmpty { "und" } }
        .distinct()
        .sorted()

    /** Decode thread: feed every data frame through here. */
    fun collect(frame: Frame) {
        val track = trackByStream[frame.streamIndex] ?: return
        val cue = EmbeddedSubtitles.parsePacket(frame, track) ?: return
        cuesByStream.computeIfAbsent(track.streamIndex) { ConcurrentSkipListMap() }[cue.startMs] = cue
    }

    /** HUD thread: the cue on screen at [positionMs], for the configured language. */
    fun activeCue(positionMs: Long): SubtitleCue? {
        val language = PremiereClientConfig.subtitleLanguage
        if (language != selectedForLanguage) {
            selectedTrack = EmbeddedSubtitles.bestTrack(tracks, language)
            selectedForLanguage = language
        }
        val track = selectedTrack ?: return null
        val cues = cuesByStream[track.streamIndex] ?: return null
        val entry = cues.floorEntry(positionMs) ?: return null
        return if (positionMs < entry.value.endMs) entry.value else null
    }
}
