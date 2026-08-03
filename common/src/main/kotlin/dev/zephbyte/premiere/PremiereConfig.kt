package dev.zephbyte.premiere

import dev.zephbyte.premiere.util.JsonConfig
import java.net.URI
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

    // --- /pm dashboard: in-game link to the dashboard served by this server.
    // The R2 credential below is deliberately the only place it lives; scope
    // the API token to this one bucket (Object Read & Write) so a leak can
    // never reach past movie files.

    /** Port for the dashboard. It signs uploads; file bytes go browser -> R2. */
    var uploadHttpPort: Int = 8477
        private set

    /** Externally reachable base for dashboard links, ideally "https://movies.example.com". */
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
    fun reload(): Boolean {
        val objectFromDisk = try {
            JsonConfig.readOrThrow(path())
        } catch (e: Exception) {
            PremiereCore.LOGGER.error("Premiere config is invalid; keeping the current values and leaving the file untouched", e)
            return false
        }

        val previousAudioDistance = audioDistance
        val previousAudioLanguage = audioLanguage
        val previousUploadHttpPort = uploadHttpPort
        val previousUploadPublicAddress = uploadPublicAddress
        val previousR2AccountId = r2AccountId
        val previousR2Bucket = r2Bucket
        val previousR2AccessKeyId = r2AccessKeyId
        val previousR2SecretAccessKey = r2SecretAccessKey

        var nextAudioDistance = previousAudioDistance
        var nextAudioLanguage = previousAudioLanguage
        var nextUploadHttpPort = previousUploadHttpPort
        var nextUploadPublicAddress = previousUploadPublicAddress
        var nextR2AccountId = previousR2AccountId
        var nextR2Bucket = previousR2Bucket
        var nextR2AccessKeyId = previousR2AccessKeyId
        var nextR2SecretAccessKey = previousR2SecretAccessKey

        try {
            objectFromDisk?.let { o ->
                o["audio_distance"]?.let { nextAudioDistance = it.asFloat }
                o["audio_language"]?.let { nextAudioLanguage = it.asString.lowercase().trim() }
                o["upload_http_port"]?.let { nextUploadHttpPort = it.asInt }
                o["upload_public_address"]?.let { nextUploadPublicAddress = it.asString.trimEnd('/') }
                o["r2_account_id"]?.let { nextR2AccountId = it.asString.trim() }
                o["r2_bucket"]?.let { nextR2Bucket = it.asString.trim() }
                o["r2_access_key_id"]?.let { nextR2AccessKeyId = it.asString.trim() }
                o["r2_secret_access_key"]?.let { nextR2SecretAccessKey = it.asString.trim() }
            }
            require(nextAudioDistance.isFinite() && nextAudioDistance > 0f) {
                "audio_distance must be a positive finite number"
            }
            require(nextUploadHttpPort in 1..65535) {
                "upload_http_port must be between 1 and 65535"
            }
            if (nextUploadPublicAddress.isNotBlank()) {
                val uri = URI(nextUploadPublicAddress)
                require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null) {
                    "upload_public_address must be an http(s) URL with a host"
                }
            }
        } catch (e: Exception) {
            PremiereCore.LOGGER.error(
                "Premiere config values are invalid; keeping the current values and leaving the file untouched",
                e,
            )
            return false
        }

        audioDistance = nextAudioDistance
        audioLanguage = nextAudioLanguage
        uploadHttpPort = nextUploadHttpPort
        uploadPublicAddress = nextUploadPublicAddress
        r2AccountId = nextR2AccountId
        r2Bucket = nextR2Bucket
        r2AccessKeyId = nextR2AccessKeyId
        r2SecretAccessKey = nextR2SecretAccessKey
        if (save()) return true

        audioDistance = previousAudioDistance
        audioLanguage = previousAudioLanguage
        uploadHttpPort = previousUploadHttpPort
        uploadPublicAddress = previousUploadPublicAddress
        r2AccountId = previousR2AccountId
        r2Bucket = previousR2Bucket
        r2AccessKeyId = previousR2AccessKeyId
        r2SecretAccessKey = previousR2SecretAccessKey
        return false
    }

    private fun save(): Boolean = JsonConfig.write(path()) {
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
