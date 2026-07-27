package dev.zephbyte.premiere

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Platform-free identity shared by the Fabric mod and the Paper plugin.
 * Loader-specific entrypoints (Fabric's Premiere, Paper's PremierePaperPlugin)
 * alias these rather than declaring their own.
 */
object PremiereCore {
    const val MOD_ID = "premiere"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
}
