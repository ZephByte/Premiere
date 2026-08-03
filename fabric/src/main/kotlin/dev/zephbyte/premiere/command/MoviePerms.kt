package dev.zephbyte.premiere.command

import dev.zephbyte.premiere.Premiere
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.permissions.Permissions

/**
 * Staff gate for everything except /movienight list. Checked against the
 * LuckPerms node when LuckPerms is installed (the whole point: staff operate
 * movie night without file access or op), falling back to vanilla gamemaster
 * (op level 2) otherwise. Console is always allowed.
 */
object MoviePerms {
    const val CONTROL_NODE = "premiere.control"
    const val LEGACY_CONTROL_NODE = "movienight.control"

    private val luckPermsLoaded: Boolean by lazy {
        FabricLoader.getInstance().isModLoaded("luckperms")
    }

    fun canControl(source: CommandSourceStack): Boolean {
        val player = source.player ?: return true
        if (luckPermsLoaded) {
            try {
                return checkLuckPerms(player.uuid)
            } catch (e: Throwable) {
                Premiere.LOGGER.warn("LuckPerms lookup failed, falling back to op check", e)
            }
        }
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
    }

    // Kept in its own method so LuckPerms classes only resolve when it runs.
    private fun checkLuckPerms(uuid: java.util.UUID): Boolean {
        val luckPerms = net.luckperms.api.LuckPermsProvider.get()
        val user = luckPerms.userManager.getUser(uuid) ?: return false
        val permissions = user.cachedData.permissionData
        return permissions.checkPermission(CONTROL_NODE).asBoolean() ||
            permissions.checkPermission(LEGACY_CONTROL_NODE).asBoolean()
    }
}
