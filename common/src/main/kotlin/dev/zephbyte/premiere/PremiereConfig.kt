package dev.zephbyte.premiere

import dev.zephbyte.premiere.util.JsonConfig
import java.nio.file.Path

/**
 * Server-side knobs, in config/premiere.json. Written with defaults on first
 * run (and rewritten after every load) so new settings stay discoverable.
 */
object PremiereConfig {

    /** Audible radius (blocks) of a screen's audio channel. */
    var audioDistance: Float = 48f
        private set

    /**
     * Preferred audio track language for multi-audio films (ISO 639 as
     * releases tag it: "eng", "jpn", ...). Blank keeps the file's default
     * track. Overridable per film: /pm play <screen> <movie> --audio jpn
     */
    var audioLanguage: String = ""
        private set

    // --- /pm upload: in-game link to the dashboard served by this server.
    // The R2 credential below is deliberately the only place it lives; scope
    // the API token to this one bucket (Object Read & Write) so a leak can
    // never reach past movie files.

    /** Port for the dashboard. It signs uploads; file bytes go browser -> R2. */
    var uploadHttpPort: Int = 8477
        private set

    /** Externally reachable base for dashboard links, e.g. "http://mc.example.com:8477". */
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

    // Set once by the platform entrypoint (Fabric: loader config dir;
    // Paper: plugin data folder) before anything reads settings.
    private lateinit var configDir: Path

    private fun path(): Path = configDir.resolve("premiere.json")

    fun load(configDir: Path) {
        this.configDir = configDir
        reload()
    }

    /** Re-reads premiere.json from the directory given at startup. */
    fun reload() {
        JsonConfig.read(path())?.let { o ->
            o["audio_distance"]?.let { audioDistance = it.asFloat }
            o["audio_language"]?.let { audioLanguage = it.asString.lowercase().trim() }
            o["upload_http_port"]?.let { uploadHttpPort = it.asInt }
            o["upload_public_address"]?.let { uploadPublicAddress = it.asString.trimEnd('/') }
            o["r2_account_id"]?.let { r2AccountId = it.asString }
            o["r2_bucket"]?.let { r2Bucket = it.asString }
            o["r2_access_key_id"]?.let { r2AccessKeyId = it.asString }
            o["r2_secret_access_key"]?.let { r2SecretAccessKey = it.asString }
        }
        save()
    }

    private fun save() = JsonConfig.write(path()) {
        addProperty("audio_distance", audioDistance)
        addProperty("audio_language", audioLanguage)
        addProperty("upload_http_port", uploadHttpPort)
        addProperty("upload_public_address", uploadPublicAddress)
        addProperty("r2_account_id", r2AccountId)
        addProperty("r2_bucket", r2Bucket)
        addProperty("r2_access_key_id", r2AccessKeyId)
        addProperty("r2_secret_access_key", r2SecretAccessKey)
    }
}
