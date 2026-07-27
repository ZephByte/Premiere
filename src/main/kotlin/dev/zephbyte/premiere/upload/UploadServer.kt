package dev.zephbyte.premiere.upload

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.PremiereConfig
import dev.zephbyte.premiere.screen.ScreenManager
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The staff dashboard: a tiny HTTP server, lazily started by the first
 * /movienight upload, showing what's playing, the movie library (with
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

    private val tokens = ConcurrentHashMap<String, Long>() // token -> expiry epoch ms
    private val random = SecureRandom()
    private var server: HttpServer? = null

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
        Premiere.LOGGER.info("Dashboard listening on port {}", PremiereConfig.uploadHttpPort)
        true
    } catch (e: Exception) {
        Premiere.LOGGER.error("Could not start dashboard on port {}", PremiereConfig.uploadHttpPort, e)
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
                        respond(exchange, 403, "text/html", EXPIRED_PAGE)
                    } else {
                        respond(exchange, 200, "text/html", dashboardPage(token!!))
                    }
                }

                exchange.requestMethod == "POST" && path.startsWith("/api/") -> handleApi(exchange, path)

                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: Exception) {
            Premiere.LOGGER.warn("Dashboard request failed: {}", e.message)
            runCatching { respond(exchange, 500, "application/json", error(e.message ?: "internal error")) }
        }
    }

    private fun handleApi(exchange: HttpExchange, path: String) {
        val body = try {
            JsonParser.parseString(exchange.requestBody.readAllBytes().decodeToString()).asJsonObject
        } catch (e: Exception) {
            respond(exchange, 400, "application/json", error("bad request"))
            return
        }
        if (!valid(body["token"]?.asString)) {
            respond(exchange, 403, "application/json", error("Session expired; run /movienight upload again."))
            return
        }
        when (path) {
            "/api/sign" -> {
                val rawName = body["name"]?.asString?.takeIf { it.isNotBlank() }
                    ?: (body["filename"]?.asString ?: "movie").substringBeforeLast('.')
                val extension = (body["filename"]?.asString ?: "").substringAfterLast('.', "mp4")
                    .lowercase().ifEmpty { "mp4" }
                val name = R2Storage.sanitizeName(rawName).substringBeforeLast('.')
                val key = "$name.$extension"
                respond(exchange, 200, "application/json", JsonObject().apply {
                    addProperty("uploadUrl", R2Storage.presignPut(key, PRESIGN_EXPIRES_S))
                    addProperty("name", name)
                }.toString())
            }

            "/api/list" -> {
                val movies = JsonArray()
                R2Storage.listObjects().forEach { obj ->
                    movies.add(JsonObject().apply {
                        addProperty("key", obj.key)
                        addProperty("name", MovieLibrary.displayName(obj.key))
                        addProperty("size", obj.size)
                        addProperty("lastModified", obj.lastModified)
                    })
                }
                respond(exchange, 200, "application/json", JsonObject().apply { add("movies", movies) }.toString())
            }

            "/api/delete" -> {
                val key = body["key"]?.asString
                if (key.isNullOrBlank()) {
                    respond(exchange, 400, "application/json", error("missing key"))
                    return
                }
                R2Storage.deleteObject(key)
                Premiere.LOGGER.info("Dashboard deleted '{}' from the movie library", key)
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
                        addProperty("positionSeconds", s.positionSeconds)
                        addProperty("volumePercent", s.volumePercent)
                    })
                }
                respond(exchange, 200, "application/json", JsonObject().apply { add("screens", screens) }.toString())
            }

            else -> respond(exchange, 404, "application/json", error("not found"))
        }
    }

    private fun error(message: String): String =
        JsonObject().apply { addProperty("error", message) }.toString()

    private fun queryParam(exchange: HttpExchange, name: String): String? =
        exchange.requestURI.rawQuery?.split('&')?.firstNotNullOfOrNull {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            if (k == name) URLDecoder.decode(v, Charsets.UTF_8) else null
        }

    private fun respond(exchange: HttpExchange, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private val EXPIRED_PAGE = """
        <!doctype html><meta charset="utf-8"><title>Link expired</title>
        <body style="font-family:system-ui;background:#14161a;color:#e8e6e3;display:grid;place-items:center;min-height:100vh">
        <p>This dashboard link is invalid or expired. Run <b>/movienight upload</b> in-game for a fresh one.</p>
    """.trimIndent()

    private fun dashboardPage(token: String) = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Movie Night Dashboard</title>
<style>
  :root { color-scheme: dark; }
  body { font-family: system-ui, sans-serif; background: #14161a; color: #e8e6e3;
         margin: 0; padding: 2rem 1rem; display: grid; justify-content: center; }
  main { width: min(720px, 94vw); display: grid; gap: 1.2rem; }
  h1 { font-size: 1.4rem; margin: 0; }
  section { background: #1a1d23; border: 1px solid #2a2e37; border-radius: 12px; padding: 1rem 1.2rem; }
  h2 { font-size: .95rem; margin: 0 0 .8rem; color: #9aa0a6; text-transform: uppercase; letter-spacing: .05em; }
  table { width: 100%; border-collapse: collapse; font-size: .9rem; }
  td, th { text-align: left; padding: .45rem .4rem; border-top: 1px solid #2a2e37; }
  th { color: #9aa0a6; font-weight: 500; border-top: none; }
  td.num { text-align: right; font-variant-numeric: tabular-nums; }
  .drop { border: 2px dashed #4a5160; border-radius: 12px; padding: 2.2rem 1rem;
          text-align: center; cursor: pointer; transition: border-color .15s; }
  .drop.hover { border-color: #8ab4f8; }
  input[type=text] { width: 100%; box-sizing: border-box; padding: .6rem; border-radius: 8px;
          border: 1px solid #4a5160; background: #1d2026; color: inherit; margin-bottom: .8rem; }
  progress { width: 100%; height: 10px; margin-top: .8rem; }
  .result { background: #14161a; border-radius: 8px; padding: .7rem; margin-top: .8rem;
            font-family: ui-monospace, monospace; font-size: .85rem; word-break: break-all; }
  button { padding: .35rem .7rem; border-radius: 6px; border: none; background: #8ab4f8;
           color: #14161a; font-weight: 600; cursor: pointer; font-size: .85rem; }
  button.danger { background: #f28b82; }
  button.ghost { background: #2a2e37; color: #e8e6e3; }
  .err { color: #f28b82; }
  .hint { color: #9aa0a6; font-size: .85rem; }
  .state { font-weight: 600; }
  .state.PLAYING { color: #81c995; }
  .state.PAUSED { color: #fdd663; }
  .state.STOPPED { color: #9aa0a6; }
  #banner { display: none; background: #3c2a2a; border: 1px solid #f28b82; border-radius: 8px;
            padding: .7rem 1rem; }
</style>
</head>
<body>
<main>
  <h1>&#127909; Movie Night Dashboard</h1>
  <div id="banner"></div>

  <section>
    <h2>Screens</h2>
    <table><thead><tr><th>Screen</th><th>Wall</th><th>Status</th><th>Now showing</th><th class="num">Position</th><th class="num">Vol</th></tr></thead>
    <tbody id="screens"><tr><td colspan="6" class="hint">Loading…</td></tr></tbody></table>
  </section>

  <section>
    <h2>Library <button class="ghost" id="reload" style="float:right">Refresh</button></h2>
    <table><thead><tr><th>Name</th><th class="num">Size</th><th>Uploaded</th><th></th><th></th></tr></thead>
    <tbody id="movies"><tr><td colspan="5" class="hint">Loading…</td></tr></tbody></table>
  </section>

  <section>
    <h2>Upload</h2>
    <input id="name" type="text" placeholder="Movie name, e.g. intro_joke (what staff types in-game)">
    <div class="drop" id="drop">Drop a video here or click to choose<br>
      <span class="hint">MP4 (H.264 + AAC) is the safe bet; MKV and MOV work too.</span></div>
    <input id="file" type="file" accept="video/mp4,video/quicktime,video/x-matroska,.mkv,.mp4,.mov" hidden>
    <progress id="bar" max="100" value="0" hidden></progress>
    <button id="cancel" class="danger" hidden style="margin-top:.6rem">Cancel upload</button>
    <div id="out"></div>
  </section>
</main>
<script>
const TOKEN = ${JsonObject().also { it.addProperty("t", token) }["t"]};
const el = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

async function api(path, body = {}) {
  const res = await fetch(path, { method: "POST", body: JSON.stringify({ token: TOKEN, ...body }) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (res.status === 403) { el("banner").style.display = "block";
      el("banner").textContent = data.error || "Session expired; run /movienight upload again."; }
    throw new Error(data.error || ("HTTP " + res.status));
  }
  return data;
}

function fmtSize(b) {
  if (b >= 1e9) return (b / 1e9).toFixed(2) + " GB";
  if (b >= 1e6) return (b / 1e6).toFixed(1) + " MB";
  return Math.max(1, Math.round(b / 1e3)) + " KB";
}
function fmtPos(s) { return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); }

async function refreshStatus() {
  try {
    const { screens } = await api("/api/status");
    el("screens").innerHTML = screens.length === 0
      ? '<tr><td colspan="6" class="hint">No screens defined. Use /movienight define in-game.</td></tr>'
      : screens.map(s =>
        '<tr><td>' + esc(s.name) + '</td><td>' + esc(s.size) + ' ' + esc(s.facing) + '</td>' +
        '<td class="state ' + esc(s.state) + '">' + esc(s.state.toLowerCase()) + '</td>' +
        '<td>' + (s.label ? esc(s.label) : '<span class="hint">—</span>') + '</td>' +
        '<td class="num">' + (s.state === "STOPPED" ? '—' : fmtPos(s.positionSeconds)) + '</td>' +
        '<td class="num">' + s.volumePercent + '%</td></tr>').join("");
  } catch (e) { /* banner already shown on 403 */ }
}

async function refreshMovies() {
  try {
    const { movies } = await api("/api/list");
    el("movies").innerHTML = movies.length === 0
      ? '<tr><td colspan="5" class="hint">Library is empty — upload something below.</td></tr>'
      : movies.map(m =>
        '<tr><td>' + esc(m.name) + '</td><td class="num">' + fmtSize(m.size) + '</td>' +
        '<td>' + esc((m.lastModified || "").slice(0, 10)) + '</td>' +
        '<td><button class="ghost" onclick="copyPlay(\'' + esc(m.name) + '\', this)">Copy play command</button></td>' +
        '<td><button class="danger" onclick="del(\'' + esc(m.key) + '\')">Delete</button></td></tr>').join("");
  } catch (e) {
    el("movies").innerHTML = '<tr><td colspan="5" class="err">' + esc(e.message) + '</td></tr>';
  }
}

function copyPlay(name, btn) {
  navigator.clipboard.writeText("/movienight play <screen> " + name)
    .then(() => { btn.textContent = "Copied!"; setTimeout(() => btn.textContent = "Copy play command", 1500); });
}

async function del(key) {
  if (!confirm("Delete '" + key + "' from the library? Anyone currently watching it will lose the stream.")) return;
  try { await api("/api/delete", { key }); refreshMovies(); }
  catch (e) { alert("Delete failed: " + e.message); }
}

const drop = el("drop"), file = el("file"), bar = el("bar"), out = el("out"), nameField = el("name");
drop.onclick = () => file.click();
drop.ondragover = (e) => { e.preventDefault(); drop.classList.add("hover"); };
drop.ondragleave = () => drop.classList.remove("hover");
drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove("hover");
                       if (e.dataTransfer.files[0]) upload(e.dataTransfer.files[0]); };
file.onchange = () => file.files[0] && upload(file.files[0]);

const cancelBtn = el("cancel");
let activeXhr = null;
cancelBtn.onclick = () => { if (activeXhr) activeXhr.abort(); };

function uploadDone() {
  bar.hidden = true; cancelBtn.hidden = true; activeXhr = null;
  drop.style.pointerEvents = ""; drop.style.opacity = "";
}

async function upload(f) {
  if (activeXhr) return; // one at a time
  out.innerHTML = "";
  if (!nameField.value.trim()) nameField.value = f.name.replace(/\.[^.]*${'$'}/, "");
  let signed;
  try { signed = await api("/api/sign", { filename: f.name, name: nameField.value }); }
  catch (e) { out.innerHTML = '<p class="err">' + esc(e.message) + '</p>'; return; }
  bar.hidden = false; bar.value = 0; cancelBtn.hidden = false;
  drop.style.pointerEvents = "none"; drop.style.opacity = .5;
  const xhr = new XMLHttpRequest();
  activeXhr = xhr;
  xhr.open("PUT", signed.uploadUrl);
  xhr.upload.onprogress = (e) => { if (e.lengthComputable) bar.value = e.loaded / e.total * 100; };
  // An aborted presigned PUT is atomic on R2's side: the object only exists
  // once the upload completes, so cancelling leaves nothing to clean up.
  xhr.onabort = () => { uploadDone();
    out.innerHTML = '<p class="hint">Upload cancelled — nothing was saved.</p>'; };
  xhr.onload = () => {
    uploadDone();
    if (xhr.status >= 200 && xhr.status < 300) {
      const cmd = "/movienight play <screen> " + signed.name;
      out.innerHTML = '<p>Uploaded as <b>' + esc(signed.name) + '</b>. Play it with:</p>' +
        '<div class="result">' + esc(cmd) + '</div>' +
        '<button id="copy">Copy command</button>';
      el("copy").onclick = () => navigator.clipboard.writeText(cmd)
        .then(() => el("copy").textContent = "Copied!");
      nameField.value = "";
      refreshMovies();
    } else {
      out.innerHTML = '<p class="err">Upload failed (' + xhr.status + '). ' +
        'If the browser console shows a CORS error, add the CORS policy from the README to the bucket.</p>';
    }
  };
  xhr.onerror = () => { uploadDone();
    out.innerHTML = '<p class="err">Upload failed (network or CORS). See the README\'s bucket setup.</p>'; };
  xhr.send(f);
}

el("reload").onclick = refreshMovies;
refreshStatus(); refreshMovies();
setInterval(refreshStatus, 5000); // local data, free to poll; the library is fetched on demand
</script>
</body>
</html>"""
}
