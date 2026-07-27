package dev.zephbyte.premiere.wire

import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.screen.ScreenDefinition

/**
 * Snapshot of one screen, sent to video-capable clients only (both platforms
 * gate on the client having declared the channel, so vanilla clients receive
 * nothing at all).
 *
 * [mediaPositionMs] is the master-clock media position at the moment the server
 * built this message; clients anchor to their local clock on receipt, so
 * server/client wall-clock skew cancels out (modulo one-way latency, well under
 * the sync tolerance).
 */
data class ScreenStateMessage(
    val screen: ScreenDefinition,
    val url: String,
    val subtitleUrl: String,
    val audioLanguage: String,
    val audioDistance: Float,
    val state: PlayState,
    val mediaPositionMs: Long,
    val volume: Float,
    val removed: Boolean,
)

/**
 * Sent by a modded client after joining to request the current state of every
 * screen. This is what makes late joiners (and mid-film installs) work; the
 * server must never rely on only pushing updates going forward.
 */
object RequestScreensMessage

/** Sent by a video client when a LOADED screen has its first frame decoded. */
data class ScreenReadyMessage(val screen: String)
