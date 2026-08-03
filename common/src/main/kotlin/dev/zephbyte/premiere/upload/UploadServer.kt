package dev.zephbyte.premiere.upload

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zephbyte.premiere.PremiereCore
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.screen.ScreenManager
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.zephbyte.premiere.screen.PlayState
import dev.zephbyte.premiere.util.Times

/**
 * The staff dashboard: a tiny HTTP server, lazily started by the first
 * /pm dashboard (or /pm dash), showing what's playing, the movie library (with
 * delete), and the upload drop zone. File bytes go browser -> R2 via
 * presigned URLs; screen status comes from a server-thread snapshot — this
 * never carries video and never touches game state off-thread.
 *
 * Access control is the session token minted by the command (which is itself
 * behind movienight.control): multi-use, expires after [TOKEN_TTL_MS]. An
 * idle listener exposes nothing but a rejection page.
 */
object UploadServer {

    private const val TOKEN_TTL_MS = 60 * 60 * 1000L
    private const val PRESIGN_EXPIRES_S = 4 * 3600L // big uploads just need to start in time
    private const val MAX_API_BODY_BYTES = 64 * 1024
    private val UPLOAD_EXTENSIONS = setOf("mp4", "mkv", "mov", "srt")

    private val tokens = ConcurrentHashMap<String, Long>() // token -> expiry epoch ms
    private val random = SecureRandom()
    private var server: HttpServer? = null

    /**
     * Address placed in staff chat. A configured HTTPS/public address always
     * wins; otherwise expose a useful LAN URL instead of an unclickable
     * placeholder. The listener itself binds all interfaces.
     */
    fun dashboardBaseAddress(): String = PremiereConfig.uploadPublicAddress.ifBlank {
        val host = discoverReachableHost() ?: "127.0.0.1"
        "http://$host:${PremiereConfig.uploadHttpPort}"
    }

    val hasPublicAddress: Boolean
        get() = PremiereConfig.uploadPublicAddress.isNotBlank()

    private fun discoverReachableHost(): String? {
        // Asking the OS which address it would route through avoids choosing a
        // Docker/VPN adapter when a machine has several private interfaces.
        val routedAddress = runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53))
                (socket.localAddress as? Inet4Address)
                    ?.takeUnless { it.isLoopbackAddress || it.isLinkLocalAddress }
                    ?.hostAddress
            }
        }.getOrNull()
        if (routedAddress != null) return routedAddress

        return runCatching {
            val candidates = mutableListOf<Inet4Address>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback || network.isVirtual) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        candidates += address
                    }
                }
            }
            candidates.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
        }.getOrNull()
    }

    /** Mints a dashboard session token, starting the listener if needed. */
    @Synchronized
    fun mintToken(): String? {
        if (!PremiereConfig.uploadConfigured) return null
        if (server == null && !start()) return null
        tokens.entries.removeIf { it.value < System.currentTimeMillis() }
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens[token] = System.currentTimeMillis() + TOKEN_TTL_MS
        return token
    }

    private fun valid(token: String?): Boolean =
        token != null && (tokens[token] ?: 0) > System.currentTimeMillis()

    private fun start(): Boolean = try {
        val s = HttpServer.create(InetSocketAddress(PremiereConfig.uploadHttpPort), 0)
        s.executor = Executors.newVirtualThreadPerTaskExecutor()
        s.createContext("/") { exchange -> exchange.use(::handle) }
        s.start()
        server = s
        PremiereCore.LOGGER.info("Dashboard listening on port {}", PremiereConfig.uploadHttpPort)
        true
    } catch (e: Exception) {
        PremiereCore.LOGGER.error("Could not start dashboard on port {}", PremiereConfig.uploadHttpPort, e)
        false
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        tokens.clear()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        try {
            when {
                exchange.requestMethod == "GET" && (path == "/dash" || path == "/upload") -> {
                    val token = queryParam(exchange, "token")
                    if (!valid(token)) {
                        respond(exchange, 403, "text/html", DashboardPage.EXPIRED)
                    } else {
                        respond(exchange, 200, "text/html", DashboardPage.render(token!!))
                    }
                }

                exchange.requestMethod == "POST" && path.startsWith("/api/") -> handleApi(exchange, path)

                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: Exception) {
            PremiereCore.LOGGER.warn("Dashboard request failed: {}", e.message)
            runCatching { respond(exchange, 500, "application/json", error(e.message ?: "internal error")) }
        }
    }

    private fun handleApi(exchange: HttpExchange, path: String) {
        if (!sameOrigin(exchange)) {
            respond(exchange, 403, "application/json", error("Cross-origin dashboard request rejected"))
            return
        }
        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_API_BODY_BYTES) {
            respond(exchange, 413, "application/json", error("request too large"))
            return
        }
        val bytes = exchange.requestBody.readNBytes(MAX_API_BODY_BYTES + 1)
        if (bytes.size > MAX_API_BODY_BYTES) {
            respond(exchange, 413, "application/json", error("request too large"))
            return
        }
        val body = try {
            JsonParser.parseString(bytes.decodeToString()).asJsonObject
        } catch (e: Exception) {
            respond(exchange, 400, "application/json", error("bad request"))
            return
        }
        if (!valid(body["token"]?.asString)) {
            respond(exchange, 403, "application/json", error("Session expired; run /pm dashboard again."))
            return
        }
        when (path) {
            "/api/sign" -> {
                val rawName = body["name"]?.asString?.takeIf { it.isNotBlank() }
                    ?: (body["filename"]?.asString ?: "movie").substringBeforeLast('.')
                val extension = (body["filename"]?.asString ?: "").substringAfterLast('.', "mp4")
                    .lowercase().ifEmpty { "mp4" }
                if (extension !in UPLOAD_EXTENSIONS) {
                    respond(
                        exchange,
                        400,
                        "application/json",
                        error("Unsupported file type '.$extension'. Use MP4, MKV, MOV, or SRT."),
                    )
                    return
                }
                val name = R2Storage.sanitizeName(rawName)
                    .substringBeforeLast('.')
                    .trim('.')
                    .ifBlank { "movie" }
                val key = "$name.$extension"
                if (R2Storage.listObjects().any { it.key.equals(key, ignoreCase = true) }) {
                    respond(
                        exchange,
                        409,
                        "application/json",
                        error("'$key' already exists. Rename or delete it before uploading a replacement."),
                    )
                    return
                }
                MovieLibrary.invalidate()
                respond(exchange, 200, "application/json", JsonObject().apply {
                    addProperty("uploadUrl", R2Storage.presignPut(key, PRESIGN_EXPIRES_S))
                    addProperty("name", name)
                }.toString())
            }

            "/api/list" -> {
                val movies = JsonArray()
                val objects = R2Storage.listObjects()
                MovieLibrary.updateCache(objects.map { it.key })
                objects.forEach { obj ->
                    movies.add(JsonObject().apply {
                        addProperty("key", obj.key)
                        addProperty("name", MovieLibrary.displayName(obj.key))
                        addProperty("size", obj.size)
                        addProperty("lastModified", obj.lastModified)
                    })
                }
                respond(exchange, 200, "application/json", JsonObject().apply { add("movies", movies) }.toString())
            }

            "/api/rename" -> {
                val key = body["key"]?.asString
                val rawNewName = body["newName"]?.asString
                if (key.isNullOrBlank() || rawNewName.isNullOrBlank()) {
                    respond(exchange, 400, "application/json", error("missing key or newName"))
                    return
                }
                val extension = key.substringAfterLast('.', "mp4")
                val newName = R2Storage.sanitizeName(rawNewName)
                    .substringBeforeLast('.')
                    .trim('.')
                    .ifBlank { "movie" }
                val newKey = "$newName.$extension"
                if (newKey == key) {
                    respond(exchange, 200, "application/json", "{}")
                    return
                }
                val objects = R2Storage.listObjects()
                val source = objects.firstOrNull { it.key == key }
                if (source == null) {
                    respond(exchange, 404, "application/json", error("'$key' is no longer in the movie library"))
                    return
                }
                if (objects.any { it.key != key && it.key.equals(newKey, ignoreCase = true) }) {
                    respond(exchange, 409, "application/json", error("A movie named '$newName' already exists"))
                    return
                }
                try {
                    R2Storage.renameObject(key, newKey, source.size)
                } catch (e: Exception) {
                    PremiereCore.LOGGER.warn("Dashboard could not rename '{}' to '{}': {}", key, newKey, e.message)
                    respond(exchange, 502, "application/json", error(e.message ?: "R2 rename failed"))
                    return
                }
                MovieLibrary.invalidate()
                PremiereCore.LOGGER.info("Dashboard renamed '{}' to '{}'", key, newKey)
                respond(exchange, 200, "application/json", JsonObject().apply {
                    addProperty("name", newName)
                }.toString())
            }

            "/api/delete" -> {
                val key = body["key"]?.asString
                if (key.isNullOrBlank()) {
                    respond(exchange, 400, "application/json", error("missing key"))
                    return
                }
                R2Storage.deleteObject(key)
                MovieLibrary.invalidate()
                PremiereCore.LOGGER.info("Dashboard deleted '{}' from the movie library", key)
                respond(exchange, 200, "application/json", "{}")
            }

            "/api/status" -> {
                val screens = JsonArray()
                ScreenManager.statusSnapshot().get(3, TimeUnit.SECONDS).forEach { s ->
                    screens.add(JsonObject().apply {
                        addProperty("name", s.name)
                        addProperty("size", s.size)
                        addProperty("facing", s.facing)
                        addProperty("state", s.state)
                        addProperty("label", s.label)
                        addProperty("url", s.url)
                        addProperty("generation", s.generation)
                        addProperty("positionMs", s.positionMs)
                        addProperty("durationMs", s.durationMs)
                        addProperty("volumePercent", s.volumePercent)
                    })
                }
                respond(exchange, 200, "application/json", JsonObject().apply { add("screens", screens) }.toString())
            }

            // Screen remote control. Each op hops to the server thread and
            // reports back a user-readable error or null.
            "/api/screen/control" -> {
                val name = body["screen"]?.asString ?: ""
                val error = when (val action = body["action"]?.asString ?: "") {
                    "toggle" -> ScreenManager.dashboardAction(name) { screen ->
                        when (screen.playback.state) {
                            PlayState.PLAYING,
                            PlayState.PAUSED,
                            -> {
                                ScreenManager.togglePause(screen); null
                            }
                            PlayState.LOADED -> {
                                ScreenManager.start(screen); null
                            }
                            else -> "Nothing is playing on '$name'"
                        }
                    }
                    "play" -> ScreenManager.dashboardAction(name) { screen ->
                        when (screen.playback.state) {
                            PlayState.PAUSED, PlayState.LOADED -> {
                                ScreenManager.start(screen); null
                            }
                            PlayState.PLAYING -> null
                            PlayState.STOPPED -> "Nothing is loaded on '$name'"
                        }
                    }
                    "pause" -> ScreenManager.dashboardAction(name) { screen ->
                        when (screen.playback.state) {
                            PlayState.PLAYING -> {
                                ScreenManager.togglePause(screen); null
                            }
                            PlayState.PAUSED, PlayState.LOADED -> null
                            PlayState.STOPPED -> "Nothing is playing on '$name'"
                        }
                    }
                    "stop" -> ScreenManager.dashboardAction(name) { screen ->
                        ScreenManager.stop(screen); null
                    }
                    "delete" -> ScreenManager.dashboardAction(name) { screen ->
                        if (ScreenManager.undefine(screen.definition.name)) null
                        else "Could not save the removal; '$name' is unchanged"
                    }
                    else -> java.util.concurrent.CompletableFuture.completedFuture("Unknown action '$action'")
                }.get(3, TimeUnit.SECONDS)
                if (error != null) {
                    respond(exchange, 400, "application/json", error(error))
                } else {
                    PremiereCore.LOGGER.info("Dashboard: {} on '{}'", body["action"]?.asString, name)
                    respond(exchange, 200, "application/json", "{}")
                }
            }

            "/api/screen/seek" -> {
                val name = body["screen"]?.asString ?: ""
                val requestedPosition = body["positionMs"]?.let {
                    runCatching { it.asLong }.getOrNull()?.takeIf { value -> value >= 0 }
                }
                val time = body["time"]?.asString ?: ""
                val error = ScreenManager.dashboardAction(name) { screen ->
                    if (screen.playback.state == PlayState.STOPPED) {
                        "Nothing is playing on '$name'"
                    } else {
                        val target = requestedPosition
                            ?: Times.parseMs(time.trim(), screen.playback.currentPositionMs())
                        if (target == null) {
                            "Can't read '$time' — use 1:23:45, 5:30, 90, +30, -30"
                        } else {
                            ScreenManager.seek(screen, target); null
                        }
                    }
                }.get(3, TimeUnit.SECONDS)
                if (error != null) respond(exchange, 400, "application/json", error(error))
                else respond(exchange, 200, "application/json", "{}")
            }

            "/api/screen/volume" -> {
                val name = body["screen"]?.asString ?: ""
                val percent = body["percent"]?.let { runCatching { it.asInt }.getOrNull() }
                if (percent == null || percent !in 0..100) {
                    respond(exchange, 400, "application/json", error("Volume must be between 0 and 100"))
                    return
                }
                val error = ScreenManager.dashboardAction(name) { screen ->
                    ScreenManager.setVolume(screen, percent / 100f)
                    null
                }.get(3, TimeUnit.SECONDS)
                if (error != null) respond(exchange, 400, "application/json", error(error))
                else respond(exchange, 200, "application/json", "{}")
            }

            "/api/screen/play" -> {
                val name = body["screen"]?.asString ?: ""
                val movie = body["movie"]?.asString?.trim() ?: ""
                if (movie.isEmpty()) {
                    respond(exchange, 400, "application/json", error("No movie given"))
                    return
                }
                // Resolution blocks (bucket + signing); we're on a virtual thread.
                val resolved = try {
                    MediaResolver.resolve(movie)
                } catch (e: Exception) {
                    respond(exchange, 400, "application/json", error(e.message ?: "Could not resolve that"))
                    return
                }
                val error = ScreenManager.dashboardAction(name) { screen ->
                    ScreenManager.play(screen, resolved.url, resolved.label, resolved.subtitleUrl)
                    null
                }.get(3, TimeUnit.SECONDS)
                if (error != null) {
                    respond(exchange, 400, "application/json", error(error))
                } else {
                    PremiereCore.LOGGER.info("Dashboard: playing '{}' on '{}'", resolved.label, name)
                    respond(exchange, 200, "application/json", "{}")
                }
            }

            "/api/config" -> respond(exchange, 200, "application/json", configJson())

            "/api/reload" -> {
                if (PremiereConfig.reload()) {
                    PremiereCore.LOGGER.info("Config reloaded from the dashboard")
                    respond(exchange, 200, "application/json", configJson())
                } else {
                    respond(
                        exchange,
                        400,
                        "application/json",
                        error("Config is invalid or could not be saved. The file was left untouched; check the server log."),
                    )
                }
            }

            else -> respond(exchange, 404, "application/json", error("not found"))
        }
    }

    /** Operator-visible settings only — never the R2 credentials. */
    private fun configJson(): String = JsonObject().apply {
        addProperty("audio_distance", PremiereConfig.audioDistance)
        addProperty("audio_language", PremiereConfig.audioLanguage.ifBlank { "(file default)" })
        addProperty("upload_http_port", PremiereConfig.uploadHttpPort)
        addProperty("upload_public_address", PremiereConfig.uploadPublicAddress)
        addProperty("r2_bucket", PremiereConfig.r2Bucket)
    }.toString()

    private fun error(message: String): String =
        JsonObject().apply { addProperty("error", message) }.toString()

    private fun queryParam(exchange: HttpExchange, name: String): String? =
        exchange.requestURI.rawQuery?.split('&')?.firstNotNullOfOrNull {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            if (k == name) URLDecoder.decode(v, Charsets.UTF_8) else null
        }

    private fun sameOrigin(exchange: HttpExchange): Boolean {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        val originUri = runCatching { URI(origin) }.getOrNull() ?: return false
        val configured = PremiereConfig.uploadPublicAddress
        if (configured.isNotBlank()) {
            val expected = runCatching { URI(configured) }.getOrNull() ?: return false
            return originUri.scheme.equals(expected.scheme, ignoreCase = true) &&
                originUri.host.equals(expected.host, ignoreCase = true) &&
                effectivePort(originUri) == effectivePort(expected)
        }
        return originUri.rawAuthority.equals(
            exchange.requestHeaders.getFirst("Host"),
            ignoreCase = true,
        )
    }

    private fun effectivePort(uri: URI): Int =
        if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80

    private fun respond(exchange: HttpExchange, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.add("X-Frame-Options", "DENY")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.add(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
                "connect-src 'self' https://*.r2.cloudflarestorage.com; media-src https: http:; " +
                "frame-ancestors 'none'; base-uri 'none'",
        )
        exchange.responseHeaders.add("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
