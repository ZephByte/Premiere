package dev.zephbyte.premiere.platform

import dev.zephbyte.premiere.geo.Vec3d
import dev.zephbyte.premiere.wire.ScreenStateMessage
import java.nio.file.Path
import java.util.UUID

/**
 * Everything the shared core needs from a server platform. Fabric implements
 * this over MinecraftServer + Fabric networking; Paper over Bukkit + plugin
 * messaging. Keep it small: each method here is a porting obligation.
 */
interface PremierePlatform {
    /** Where premiere_screens.json lives — the world root on both platforms. */
    val screensFile: Path

    /** Runs [task] on the main server thread (immediately if already on it). */
    fun runOnServerThread(task: () -> Unit)

    fun onlinePlayers(): List<PlayerHandle>

    fun player(uuid: UUID): PlayerHandle?
}

interface PlayerHandle {
    val uuid: UUID
    val name: String

    /** Dimension id in "minecraft:overworld" form — must match [dev.zephbyte.premiere.screen.ScreenDefinition.dimension]. */
    val dimension: String

    val position: Vec3d

    /**
     * True when this client declared the premiere screen-state channel, i.e.
     * runs the client mod. Vanilla clients must never be sent our payloads.
     */
    val canReceiveScreenState: Boolean

    fun sendScreenState(msg: ScreenStateMessage)

    fun sendChat(text: String)

    /**
     * Tells the staff member who loaded a movie that a client is ready.
     * Platforms override this to provide their native styled, clickable
     * command component; the text fallback keeps lightweight test handles
     * and future platform ports source-compatible.
     */
    fun sendLoadReady(mediaLabel: String, reporterName: String, screenName: String) {
        sendChat("'$mediaLabel' is ready on $screenName. Buffered by $reporterName. Run /pm play $screenName to roll.")
    }

    fun sendActionBar(text: String)

    fun hasControlPermission(): Boolean
}
