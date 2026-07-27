package dev.zephbyte.premiere

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Server-side knobs, in config/premiere.json. Written with defaults on first
 * run so operators can discover them.
 */
object PremiereConfig {

    /**
     * How far ahead of the master clock the audio track is fed into Simple
     * Voice Chat, compensating for its encode/transport/jitter-buffer latency.
     * If the sound arrives after the picture, increase; if before, decrease.
     */
    var audioLeadMs: Long = 150
        private set

    /** Audible radius (blocks) of a screen's audio channel. */
    var audioDistance: Float = 48f
        private set

    /**
     * Preferred audio track language for multi-audio films (ISO 639 as
     * releases tag it: "eng", "jpn", ...). Blank keeps the file's default
     * track. Overridable per film: /movienight play <screen> <movie> --audio jpn
     */
    var audioLanguage: String = ""
        private set

    // --- /movienight upload: in-game link to a drag-and-drop page served by
    // this server that uploads straight to the operator's R2 bucket. The
    // credential below is deliberately the only place it lives; scope the API
    // token to this one bucket (Object Read & Write) so a leak can never
    // reach past movie files.

    /** Port for the upload page. The page signs uploads; file bytes go browser -> R2. */
    var uploadHttpPort: Int = 8477
        private set

    /** Externally reachable base for upload links, e.g. "http://mc.example.com:8477". */
    var uploadPublicAddress: String = ""
        private set

    var r2AccountId: String = ""
        private set
    var r2Bucket: String = ""
        private set
    var r2AccessKeyId: String = ""
        private set
    var r2SecretAccessKey: String = ""
        private set

    val uploadConfigured: Boolean
        get() = r2AccountId.isNotBlank() && r2Bucket.isNotBlank() &&
            r2AccessKeyId.isNotBlank() && r2SecretAccessKey.isNotBlank() &&
            uploadHttpPort > 0

    fun load() {
        val path = FabricLoader.getInstance().configDir.resolve("premiere.json")
        try {
            if (Files.exists(path)) {
                val o = JsonParser.parseString(Files.readString(path)).asJsonObject
                o["audio_lead_ms"]?.let { audioLeadMs = it.asLong }
                o["audio_distance"]?.let { audioDistance = it.asFloat }
                o["audio_language"]?.let { audioLanguage = it.asString.lowercase().trim() }
                o["upload_http_port"]?.let { uploadHttpPort = it.asInt }
                o["upload_public_address"]?.let { uploadPublicAddress = it.asString.trimEnd('/') }
                o["r2_account_id"]?.let { r2AccountId = it.asString }
                o["r2_bucket"]?.let { r2Bucket = it.asString }
                o["r2_access_key_id"]?.let { r2AccessKeyId = it.asString }
                o["r2_secret_access_key"]?.let { r2SecretAccessKey = it.asString }
                // Keep the file discoverable: rewrite it with any new keys.
                write(path)
            } else {
                Files.createDirectories(path.parent)
                write(path)
            }
        } catch (e: Exception) {
            Premiere.LOGGER.warn("Could not read {}; using defaults", path, e)
        }
    }

    private fun write(path: java.nio.file.Path) {
        val o = JsonObject().apply {
            addProperty("audio_lead_ms", audioLeadMs)
            addProperty("audio_distance", audioDistance)
            addProperty("audio_language", audioLanguage)
            addProperty("upload_http_port", uploadHttpPort)
            addProperty("upload_public_address", uploadPublicAddress)
            addProperty("r2_account_id", r2AccountId)
            addProperty("r2_bucket", r2Bucket)
            addProperty("r2_access_key_id", r2AccessKeyId)
            addProperty("r2_secret_access_key", r2SecretAccessKey)
        }
        Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(o))
    }
}
