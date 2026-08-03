package dev.zephbyte.premiere.upload

import com.google.gson.JsonPrimitive

/**
 * The dashboard's HTML, kept out of the server code: one self-contained page
 * (no external assets), one session token baked in.
 */
object DashboardPage {

    /** JSON string literal additionally protected from ending the script tag. */
    private fun scriptString(value: String): String = JsonPrimitive(value).toString()
        .replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    val EXPIRED = """
        <!doctype html><meta charset="utf-8"><title>Link expired</title>
        <body style="font-family:system-ui;background:#14161a;color:#e8e6e3;display:grid;place-items:center;min-height:100vh">
        <p>This dashboard link is invalid or expired. Run <b>/pm dashboard</b> in-game for a fresh one.</p>
    """.trimIndent()

    fun render(token: String) = """
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
  main { width: min(1040px, 94vw); display: grid; gap: 1rem; }
  h1 { font-size: 1.4rem; margin: 0 0 .4rem; text-align: center; }
  section { background: #1a1d23; border: 1px solid #2a2e37; border-radius: 12px; padding: 1rem 1.2rem; }
  h2 { font-size: .95rem; margin: 0 0 .8rem; color: #9aa0a6; text-transform: uppercase; letter-spacing: .05em; }
  table { width: 100%; border-collapse: collapse; font-size: .9rem; }
  td, th { text-align: left; padding: .45rem .4rem; border-top: 1px solid #2a2e37; }
  th { color: #9aa0a6; font-weight: 500; border-top: none; }
  td.num { text-align: right; font-variant-numeric: tabular-nums; }
  .drop { border: 2px dashed #4a5160; border-radius: 12px; padding: 2.2rem 1rem;
          text-align: center; cursor: pointer; transition: border-color .15s; }
  .drop.hover { border-color: #8ab4f8; }
  input[type=text], input[type=search] { width: 100%; box-sizing: border-box; padding: .6rem; border-radius: 8px;
          border: 1px solid #4a5160; background: #1d2026; color: inherit; margin-bottom: .8rem; }
  progress { width: 100%; height: 10px; margin-top: .8rem; }
  .result { background: #14161a; border-radius: 8px; padding: .7rem; margin-top: .8rem;
            font-family: ui-monospace, monospace; font-size: .85rem; word-break: break-all; }
  button { padding: .35rem .7rem; border-radius: 6px; border: none; background: #8ab4f8;
           color: #14161a; font-weight: 600; cursor: pointer; font-size: .85rem; }
  button.danger { background: #f28b82; }
  button.ghost { background: #2a2e37; color: #e8e6e3; }
  button:disabled { opacity: .42; cursor: not-allowed; }
  .err { color: #f28b82; }
  .hint { color: #9aa0a6; font-size: .85rem; }
  .state { font-weight: 600; }
  .state.PLAYING { color: #81c995; }
  .state.PAUSED { color: #fdd663; }
  .state.STOPPED { color: #9aa0a6; }
  .state.LOADED { color: #8ab4f8; }
  #banner { display: none; background: #3c2a2a; border: 1px solid #f28b82; border-radius: 8px;
            padding: .7rem 1rem; }
  nav { display: flex; gap: .4rem; }
  nav button { flex: 1; background: #1a1d23; color: #9aa0a6; border: 1px solid #2a2e37;
               padding: .55rem 0; font-size: .9rem; text-align: center; }
  nav button.active { background: #2a2e37; color: #e8e6e3; }
  section[hidden] { display: none; }
  .kv td:first-child { color: #9aa0a6; width: 45%; }
  .actions { white-space: nowrap; text-align: right; }
  .actions button { padding: .3rem .5rem; margin-left: .25rem; font-size: .8rem; }
  .screen-grid { display: grid; grid-template-columns: minmax(250px, 1fr) minmax(360px, 1.7fr); gap: 1rem; }
  .screen-list { min-width: 0; overflow-x: auto; }
  #screens tr { cursor: pointer; transition: background .12s; }
  #screens tr:hover, #screens tr.selected { background: #252a33; }
  .player { min-width: 0; background: #111318; border: 1px solid #303640; border-radius: 12px;
            overflow: hidden; align-self: start; }
  .video-shell { position: relative; aspect-ratio: 16 / 9; background: #08090b;
                 display: grid; place-items: center; overflow: hidden; }
  #preview { width: 100%; height: 100%; object-fit: contain; display: block; cursor: pointer; }
  .video-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #777f8d;
                 text-align: center; padding: 2rem; box-sizing: border-box; }
  .video-empty[hidden], .buffering[hidden] { display: none; }
  .buffering { position: absolute; inset: 0; display: grid; place-items: center;
               background: rgb(0 0 0 / 35%); pointer-events: none; }
  .spinner { width: 34px; height: 34px; border: 4px solid rgb(255 255 255 / 24%);
             border-top-color: #fff; border-radius: 50%; animation: spin .8s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .player-body { padding: .9rem 1rem 1rem; }
  .player-title { display: flex; justify-content: space-between; gap: 1rem; align-items: baseline; }
  .player-title strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .timeline { width: 100%; margin: .85rem 0 .35rem; accent-color: #8ab4f8; }
  .transport { display: grid; grid-template-columns: auto auto auto auto 1fr; gap: .45rem; align-items: center; }
  .transport button { min-width: 42px; min-height: 36px; }
  #playPause { background: #8ab4f8; min-width: 54px; }
  .timecode { text-align: right; font-variant-numeric: tabular-nums; color: #c4c8ce; font-size: .85rem; }
  .volume-row { display: grid; grid-template-columns: auto 1fr 3.2rem; align-items: center;
                gap: .6rem; margin-top: .8rem; color: #9aa0a6; font-size: .85rem; }
  .volume-row input { width: 100%; accent-color: #8ab4f8; }
  .player-foot { display: flex; justify-content: space-between; gap: .5rem; margin-top: .85rem; }
  dialog { width: min(580px, calc(100vw - 2rem)); box-sizing: border-box; border: 1px solid #3a404b;
           border-radius: 14px; padding: 0; background: #1a1d23; color: #e8e6e3;
           box-shadow: 0 18px 70px rgb(0 0 0 / 55%); }
  dialog::backdrop { background: rgb(0 0 0 / 68%); backdrop-filter: blur(2px); }
  .dialog-body { padding: 1.1rem 1.2rem 1.2rem; }
  .dialog-title { display: flex; justify-content: space-between; align-items: start; gap: 1rem;
                  margin-bottom: .9rem; }
  .dialog-title h3 { margin: 0; font-size: 1.05rem; }
  .dialog-title p { margin: .25rem 0 0; }
  .icon-button { padding: .2rem .5rem; font-size: 1.1rem; line-height: 1.2; }
  .movie-choices { display: grid; gap: .4rem; max-height: min(54vh, 460px); overflow-y: auto;
                   padding-right: .2rem; }
  .movie-choice { display: flex; width: 100%; align-items: center; justify-content: space-between;
                  gap: 1rem; padding: .7rem .8rem; text-align: left; background: #242832;
                  color: #e8e6e3; border: 1px solid #343a46; box-sizing: border-box; overflow: hidden; }
  .movie-choice:hover, .movie-choice:focus-visible { border-color: #8ab4f8; background: #2a303b; }
  .movie-choice-name { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .movie-choice-meta { flex: none; color: #9aa0a6; font-size: .78rem; font-weight: 500; white-space: nowrap; }
  .dialog-actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: .35rem; }
  .dialog-error { min-height: 1.2rem; margin: 0 0 .4rem; }
  @media (max-width: 760px) {
    body { padding: 1rem .5rem; }
    .screen-grid { grid-template-columns: 1fr; }
    .transport { grid-template-columns: auto auto auto 1fr; }
    .timecode { grid-column: 1 / -1; text-align: left; }
    table { font-size: .82rem; }
  }
</style>
</head>
<body>
<main>
  <h1>&#127909; Movie Night Dashboard</h1>
  <div id="banner"></div>

  <nav>
    <button data-tab="screens" class="active">Screens</button>
    <button data-tab="library">Library</button>
    <button data-tab="uploadTab">Upload</button>
    <button data-tab="settings">Settings</button>
  </nav>

  <section id="tab-screens">
    <h2>Screens</h2>
    <div class="screen-grid">
      <div class="screen-list">
        <table><thead><tr><th>Screen</th><th>Status</th><th class="num">Position</th></tr></thead>
        <tbody id="screens"><tr><td colspan="3" class="hint">Loading…</td></tr></tbody></table>
        <p class="hint">Choose a screen to operate it. Preview comes directly from storage; theater playback remains client-side.</p>
      </div>
      <div class="player" id="playerPanel" hidden>
        <div class="video-shell">
          <video id="preview" muted playsinline preload="metadata"></video>
          <div class="video-empty" id="videoEmpty">Nothing is playing on this screen.<br>Choose a movie below to begin.</div>
          <div class="buffering" id="previewLoading" hidden><div class="spinner" aria-label="Loading preview"></div></div>
        </div>
        <div class="player-body">
          <div class="player-title">
            <strong id="nowPlaying">Nothing playing</strong>
            <span class="state STOPPED" id="playerState">stopped</span>
          </div>
          <input class="timeline" id="timeline" type="range" min="0" max="1" value="0" step="0.1"
                 aria-label="Playback position" disabled>
          <div class="transport">
            <button class="ghost" id="back10" title="Back 10 seconds" disabled>&#8634; 10</button>
            <button id="playPause" title="Play or pause" disabled>&#9654;</button>
            <button class="ghost" id="forward10" title="Forward 10 seconds" disabled>10 &#8635;</button>
            <button class="danger" id="stopPlayback" title="Stop playback" disabled>&#9632; Stop</button>
            <span class="timecode"><span id="currentTime">0:00</span> / <span id="duration">--:--</span></span>
          </div>
          <label class="volume-row">Theater volume
            <input id="screenVolume" type="range" min="0" max="100" value="100" step="1" disabled>
            <span id="volumeValue">100%</span>
          </label>
          <div class="player-foot">
            <button class="ghost" id="chooseMovie">Choose movie</button>
            <button class="ghost" id="togglePreview" disabled>Enable live preview</button>
            <button class="danger" id="deleteScreen">Delete screen</button>
          </div>
        </div>
      </div>
    </div>
  </section>

  <section id="tab-library" hidden>
    <h2>Library <button class="ghost" id="reload" style="float:right">Refresh</button></h2>
    <table><thead><tr><th>Name</th><th class="num">Size</th><th>Uploaded</th><th></th><th></th><th></th></tr></thead>
    <tbody id="movies"><tr><td colspan="6" class="hint">Loading…</td></tr></tbody></table>
  </section>

  <section id="tab-uploadTab" hidden>
    <h2>Upload</h2>
    <input id="name" type="text" placeholder="Movie name, e.g. intro_joke (what staff types in-game)">
    <div class="drop" id="drop">Drop a video here or click to choose<br>
      <span class="hint">MP4 (H.264 + AAC) is the safe bet; MKV and MOV work too.<br>
      Subtitles: upload an .srt with the same name as the movie.</span></div>
    <input id="file" type="file" accept="video/mp4,video/quicktime,video/x-matroska,.mkv,.mp4,.mov,.srt" hidden>
    <progress id="bar" max="100" value="0" hidden></progress>
    <button id="cancel" class="danger" hidden style="margin-top:.6rem">Cancel upload</button>
    <div id="out"></div>
  </section>

  <section id="tab-settings" hidden>
    <h2>Server Settings <button class="ghost" id="reloadCfg" style="float:right">Reload config</button></h2>
    <table class="kv"><tbody id="config"><tr><td class="hint">Loading…</td></tr></tbody></table>
    <p class="hint">Edit values in <code>config/premiere.json</code> on the server, then hit
    Reload (or run /movienight reload). Audio settings apply from the next play.</p>
  </section>
</main>

<dialog id="moviePicker" aria-labelledby="moviePickerHeading">
  <div class="dialog-body">
    <div class="dialog-title">
      <div>
        <h3 id="moviePickerHeading">Choose a movie</h3>
        <p class="hint" id="moviePickerScreen"></p>
      </div>
      <button class="ghost icon-button" id="closeMoviePicker" type="button" aria-label="Close">&times;</button>
    </div>
    <input id="movieSearch" type="search" placeholder="Search your movie library" autocomplete="off"
           aria-label="Search movies">
    <div class="movie-choices" id="movieChoices"><p class="hint">Loading movies…</p></div>
  </div>
</dialog>

<dialog id="renameDialog" aria-labelledby="renameHeading">
  <form class="dialog-body" id="renameForm">
    <div class="dialog-title">
      <div>
        <h3 id="renameHeading">Rename library item</h3>
        <p class="hint" id="renameCurrent"></p>
      </div>
      <button class="ghost icon-button" id="closeRename" type="button" aria-label="Close">&times;</button>
    </div>
    <label for="renameName">New name</label>
    <input id="renameName" type="text" autocomplete="off" required>
    <p class="err dialog-error" id="renameError" aria-live="polite"></p>
    <div class="dialog-actions">
      <button class="ghost" id="cancelRename" type="button">Cancel</button>
      <button id="saveRename" type="submit">Rename</button>
    </div>
  </form>
</dialog>
<script>
const TOKEN = ${scriptString(token)};
const el = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
// JSON supplies JavaScript string escaping; HTML escaping then keeps the
// generated value inside its quoted onclick attribute.
const jsarg = (s) => esc(JSON.stringify(String(s)));

async function api(path, body = {}) {
  const res = await fetch(path, { method: "POST", body: JSON.stringify({ token: TOKEN, ...body }) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (res.status === 403) { el("banner").style.display = "block";
      el("banner").textContent = data.error || "Session expired; run /pm dashboard again."; }
    throw new Error(data.error || ("HTTP " + res.status));
  }
  return data;
}

function fmtSize(b) {
  if (b >= 1e9) return (b / 1e9).toFixed(2) + " GB";
  if (b >= 1e6) return (b / 1e6).toFixed(1) + " MB";
  return Math.max(1, Math.round(b / 1e3)) + " KB";
}
function fmtPos(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
  return h ? h + ":" + String(m).padStart(2, "0") + ":" + String(s).padStart(2, "0")
           : m + ":" + String(s).padStart(2, "0");
}

const preview = el("preview"), timeline = el("timeline"), playerPanel = el("playerPanel");
const videoEmpty = el("videoEmpty"), previewLoading = el("previewLoading");
const screenVolume = el("screenVolume");
let latestScreens = [];
let latestMovies = [];
let selectedScreenName = null;
let moviePickerScreen = null;
let renameKey = null;
let previewUrl = "";
let previewGeneration = -1;
let previewFailedUrl = "";
let livePreviewEnabled = false;
let previewWasStreaming = false;
let scrubbing = false;
let statusRefreshing = false;

function selectedScreen() {
  return latestScreens.find(s => s.name === selectedScreenName);
}

function serverSeconds(s) {
  const elapsed = s.state === "PLAYING" ? Date.now() - s.receivedAtMs : 0;
  return Math.max(0, (s.positionMs + elapsed) / 1000);
}

function chooseScreen(name) {
  selectedScreenName = name;
  renderScreenRows();
  syncPlayer(true);
}

function renderScreenRows() {
  el("screens").innerHTML = latestScreens.length === 0
    ? '<tr><td colspan="3" class="hint">No screens defined. Use /pm wand + /pm define in-game.</td></tr>'
    : latestScreens.map(s => {
      const active = s.state !== "STOPPED";
      return '<tr class="' + (s.name === selectedScreenName ? "selected" : "") +
        '" onclick="chooseScreen(' + jsarg(s.name) + ')">' +
        '<td><strong>' + esc(s.name) + '</strong><br><span class="hint">' +
          esc(s.size) + ' ' + esc(s.facing) + '</span></td>' +
        '<td class="state ' + esc(s.state) + '">' + esc(s.state.toLowerCase()) +
          (active ? '<br><span class="hint">' + esc(s.label || "") + '</span>' : '') + '</td>' +
        '<td class="num">' + (active ? fmtPos(serverSeconds(s)) : '—') + '</td></tr>';
    }).join("");
}

async function refreshStatus() {
  if (statusRefreshing) return;
  statusRefreshing = true;
  try {
    const { screens } = await api("/api/status");
    const receivedAtMs = Date.now();
    latestScreens = screens
      .map(s => ({ ...s, receivedAtMs }))
      .sort((a, b) => a.name.localeCompare(b.name));
    if (!selectedScreenName || !latestScreens.some(s => s.name === selectedScreenName)) {
      selectedScreenName = latestScreens.find(s => s.state !== "STOPPED")?.name ||
        latestScreens[0]?.name || null;
    }
    renderScreenRows();
    syncPlayer(false);
  } catch (e) { /* banner already shown on 403 */ }
  finally { statusRefreshing = false; }
}

async function ctl(screen, action) {
  try {
    if (livePreviewEnabled) previewLoading.hidden = false;
    await api("/api/screen/control", { screen, action });
    await refreshStatus();
  }
  catch (e) { alert(e.message); }
}

async function seekScreen(screen, timeOrPosition) {
  try {
    if (livePreviewEnabled) previewLoading.hidden = false;
    const body = typeof timeOrPosition === "number"
      ? { screen, positionMs: Math.max(0, Math.round(timeOrPosition)) }
      : { screen, time: timeOrPosition };
    await api("/api/screen/seek", body);
    await refreshStatus();
  }
  catch (e) { alert(e.message); }
}

function setPreviewPosition(seconds) {
  if (preview.readyState > 0 && Number.isFinite(seconds)) {
    try { preview.currentTime = Math.max(0, seconds); } catch (e) { /* metadata not ready */ }
  }
}

function mediaDurationSeconds(s) {
  const decoderDuration = Number(s && s.durationMs) / 1000;
  if (Number.isFinite(decoderDuration) && decoderDuration > 0) return decoderDuration;
  const browserDuration = Number(preview.duration);
  return Number.isFinite(browserDuration) && browserDuration > 0 ? browserDuration : 0;
}

function livePreviewVisible() {
  return livePreviewEnabled && !document.hidden && !el("tab-screens").hidden;
}

function syncPlayer(force) {
  const s = selectedScreen();
  playerPanel.hidden = !s;
  if (!s) return;

  const active = s.state !== "STOPPED" && !!s.url;
  const authoritativeSeconds = serverSeconds(s);
  el("nowPlaying").textContent = active ? (s.label || "Untitled movie") : "Nothing playing";
  el("playerState").textContent = s.state.toLowerCase();
  el("playerState").className = "state " + s.state;
  ["timeline", "playPause", "back10", "forward10", "stopPlayback"].forEach(id => el(id).disabled = !active);
  screenVolume.disabled = !active;
  el("togglePreview").disabled = !active;
  el("togglePreview").textContent = livePreviewEnabled ? "Disable live preview" : "Enable live preview";
  el("togglePreview").setAttribute("aria-pressed", String(livePreviewEnabled));
  el("playPause").innerHTML = s.state === "PLAYING" ? "&#10074;&#10074;" : "&#9654;";
  if (document.activeElement !== screenVolume) {
    screenVolume.value = s.volumePercent;
    el("volumeValue").textContent = s.volumePercent + "%";
  }

  if (!active) {
    if (previewUrl) {
      preview.pause();
      preview.removeAttribute("src");
      preview.load();
    }
    previewUrl = "";
    previewGeneration = -1;
    previewFailedUrl = "";
    previewWasStreaming = false;
    videoEmpty.textContent = "Nothing is playing on this screen. Choose a movie below to begin.";
    videoEmpty.hidden = false;
    previewLoading.hidden = true;
    timeline.value = 0;
    timeline.max = 1;
    el("currentTime").textContent = "0:00";
    el("duration").textContent = "--:--";
    return;
  }

  const sourceChanged = previewUrl !== s.url;
  const timelineChanged = previewGeneration !== s.generation;
  const streamingPreview = livePreviewVisible();
  if (previewWasStreaming && !streamingPreview && previewUrl) {
    // Pause alone can leave a browser aggressively filling its buffer. Reload
    // with preload=metadata to cancel that transfer while retaining duration.
    preview.pause();
    preview.preload = "metadata";
    preview.load();
  } else if (!previewWasStreaming && streamingPreview && previewUrl) {
    // A metadata-only element may already have fired canplay while hidden.
    // Reload at normal priority so enabling preview reliably paints a frame.
    preview.preload = "auto";
    preview.load();
  }
  previewWasStreaming = streamingPreview;
  if (sourceChanged) {
    previewUrl = s.url;
    previewGeneration = s.generation;
    previewFailedUrl = "";
    videoEmpty.hidden = !streamingPreview;
    previewLoading.hidden = !streamingPreview;
    preview.preload = streamingPreview ? "auto" : "metadata";
    preview.src = s.url;
    preview.load();
  } else if (timelineChanged) {
    previewGeneration = s.generation;
    previewLoading.hidden = !streamingPreview;
  }

  if (streamingPreview && !scrubbing) {
    const drift = Math.abs((preview.currentTime || 0) - authoritativeSeconds);
    if (force || sourceChanged || timelineChanged || drift > 1.25) setPreviewPosition(authoritativeSeconds);
  }

  if (streamingPreview && s.state === "PLAYING" && previewFailedUrl !== previewUrl) {
    preview.play().catch(() => { /* waiting/error handlers provide the visible state */ });
  } else {
    preview.pause();
  }

  const knownDuration = mediaDurationSeconds(s);
  const displaySeconds = knownDuration ? Math.min(authoritativeSeconds, knownDuration) : authoritativeSeconds;
  // Decoder metadata is authoritative and remains available even when the
  // browser cannot decode MKV/HEVC. Before either side reports metadata, keep
  // a generous seek surface instead of pretending only the next buffered
  // minute exists.
  timeline.max = Math.max(1, knownDuration || Math.max(authoritativeSeconds + 60, 6 * 3600));
  if (!scrubbing) timeline.value = Math.min(Number(timeline.max), displaySeconds);
  el("currentTime").textContent = fmtPos(scrubbing ? Number(timeline.value) : displaySeconds);
  el("duration").textContent = knownDuration ? fmtPos(knownDuration) : "--:--";
  if (!livePreviewEnabled) {
    videoEmpty.textContent = "Live preview is off to protect the theater stream. The timeline and controls stay synchronized.";
    videoEmpty.hidden = false;
    previewLoading.hidden = true;
  } else if (!streamingPreview) {
    videoEmpty.textContent = "Live preview pauses while this tab is hidden.";
    videoEmpty.hidden = false;
    previewLoading.hidden = true;
  } else if (previewFailedUrl === previewUrl) {
    videoEmpty.textContent = "This browser cannot preview this file's container or codecs. MP4 with H.264 video and AAC audio is the most compatible. Theater playback is unaffected.";
    videoEmpty.hidden = false;
    previewLoading.hidden = true;
  } else if (preview.readyState >= 2) {
    // canplay may have fired while preview was disabled; don't leave the
    // explanatory overlay covering an already decoded paused frame.
    videoEmpty.hidden = true;
    if (!preview.seeking) previewLoading.hidden = true;
  }
}

async function playMovie(screen, movie) {
  if (livePreviewEnabled) previewLoading.hidden = false;
  try {
    await api("/api/screen/play", { screen, movie });
    await refreshStatus();
  } catch (e) {
    previewLoading.hidden = true;
    alert("Could not start movie: " + e.message);
  }
}
function delScreen(screen) {
  if (!confirm("Delete screen '" + screen + "'? This stops any playback and removes its definition.")) return;
  ctl(screen, "delete");
}

preview.addEventListener("loadedmetadata", () => {
  const s = selectedScreen();
  if (s) {
    if (livePreviewVisible()) setPreviewPosition(serverSeconds(s));
    syncPlayer(false);
  }
});
preview.addEventListener("canplay", () => {
  if (livePreviewVisible()) videoEmpty.hidden = true;
  previewLoading.hidden = true;
});
preview.addEventListener("waiting", () => {
  if (livePreviewVisible() && previewFailedUrl !== previewUrl) previewLoading.hidden = false;
});
preview.addEventListener("seeking", () => {
  if (livePreviewVisible() && previewFailedUrl !== previewUrl) previewLoading.hidden = false;
});
preview.addEventListener("playing", () => { videoEmpty.hidden = true; previewLoading.hidden = true; });
preview.addEventListener("seeked", () => { if (preview.readyState >= 2) previewLoading.hidden = true; });
preview.addEventListener("error", () => {
  previewFailedUrl = previewUrl;
  syncPlayer(false);
});
preview.onclick = () => {
  const s = selectedScreen();
  if (s && s.state !== "STOPPED") ctl(s.name, s.state === "PLAYING" ? "pause" : "play");
};

timeline.addEventListener("input", () => {
  scrubbing = true;
  const seconds = Number(timeline.value);
  el("currentTime").textContent = fmtPos(seconds);
  if (livePreviewVisible()) setPreviewPosition(seconds);
});
timeline.addEventListener("change", async () => {
  const s = selectedScreen();
  const positionMs = Number(timeline.value) * 1000;
  scrubbing = false;
  if (s) await seekScreen(s.name, positionMs);
});

el("playPause").onclick = () => {
  const s = selectedScreen();
  if (s) ctl(s.name, s.state === "PLAYING" ? "pause" : "play");
};
el("back10").onclick = () => {
  const s = selectedScreen();
  if (s) seekScreen(s.name, serverSeconds(s) * 1000 - 10_000);
};
el("forward10").onclick = () => {
  const s = selectedScreen();
  if (s) seekScreen(s.name, serverSeconds(s) * 1000 + 10_000);
};
el("stopPlayback").onclick = () => {
  const s = selectedScreen();
  if (s) ctl(s.name, "stop");
};
el("chooseMovie").onclick = () => {
  const s = selectedScreen();
  if (s) openMoviePicker(s.name);
};
el("togglePreview").onclick = () => {
  livePreviewEnabled = !livePreviewEnabled;
  syncPlayer(true);
};
el("deleteScreen").onclick = () => {
  const s = selectedScreen();
  if (s) delScreen(s.name);
};
screenVolume.addEventListener("input", () => {
  el("volumeValue").textContent = screenVolume.value + "%";
});
screenVolume.addEventListener("change", async () => {
  const s = selectedScreen();
  if (!s) return;
  try {
    await api("/api/screen/volume", { screen: s.name, percent: Number(screenVolume.value) });
    await refreshStatus();
  } catch (e) { alert("Volume failed: " + e.message); }
});

async function loadMovies() {
  const { movies } = await api("/api/list");
  latestMovies = movies.sort((a, b) => a.name.localeCompare(b.name));
  return latestMovies;
}

function renderMovieLibrary() {
  const movies = latestMovies;
  el("movies").innerHTML = movies.length === 0
    ? '<tr><td colspan="6" class="hint">Library is empty — upload something below.</td></tr>'
    : movies.map(m => {
      const isSub = m.key.toLowerCase().endsWith(".srt");
      return '<tr><td>' + esc(m.name) + (isSub ? ' <span class="hint">(subtitles)</span>' : '') + '</td>' +
      '<td class="num">' + fmtSize(m.size) + '</td>' +
      '<td>' + esc((m.lastModified || "").slice(0, 10)) + '</td>' +
      '<td>' + (isSub ? '' : '<button class="ghost" onclick="copyPlay(' + jsarg(m.name) + ', this)">Copy play command</button>') + '</td>' +
      '<td><button class="ghost" onclick="openRename(' + jsarg(m.key) + ', ' + jsarg(m.name) + ')">Rename</button></td>' +
      '<td><button class="danger" onclick="del(' + jsarg(m.key) + ')">Delete</button></td></tr>';
    }).join("");
}

async function refreshMovies() {
  try {
    await loadMovies();
    renderMovieLibrary();
  } catch (e) {
    el("movies").innerHTML = '<tr><td colspan="6" class="err">' + esc(e.message) + '</td></tr>';
  }
}

function renderMovieChoices() {
  const query = el("movieSearch").value.trim().toLowerCase();
  const choices = latestMovies.filter(m =>
    !m.key.toLowerCase().endsWith(".srt") && (!query || m.name.toLowerCase().includes(query))
  );
  el("movieChoices").innerHTML = choices.length === 0
    ? '<p class="hint">' + (latestMovies.some(m => !m.key.toLowerCase().endsWith(".srt"))
        ? 'No movies match that search.'
        : 'No movies yet. Upload one from the Upload tab first.') + '</p>'
    : choices.map(m =>
        '<button class="movie-choice" type="button" data-movie-key="' + esc(m.key) + '">' +
          '<span class="movie-choice-name">&#127909; ' + esc(m.name) + '</span>' +
          '<span class="movie-choice-meta">' + fmtSize(m.size) + '</span>' +
        '</button>'
      ).join("");
  el("movieChoices").querySelectorAll("[data-movie-key]").forEach(btn => {
    btn.onclick = async () => {
      const screen = moviePickerScreen;
      if (!screen) return;
      el("moviePicker").close();
      await playMovie(screen, btn.dataset.movieKey);
    };
  });
}

async function openMoviePicker(screen) {
  moviePickerScreen = screen;
  el("moviePickerScreen").textContent = "Play on " + screen;
  el("movieSearch").value = "";
  el("movieChoices").innerHTML = '<p class="hint">Loading movies…</p>';
  el("moviePicker").showModal();
  try {
    await loadMovies();
    renderMovieChoices();
    el("movieSearch").focus();
  } catch (e) {
    el("movieChoices").innerHTML = '<p class="err">Could not load movies: ' + esc(e.message) + '</p>';
  }
}

function openRename(key, currentName) {
  renameKey = key;
  el("renameCurrent").textContent = "Current name: " + currentName;
  el("renameName").value = currentName;
  el("renameError").textContent = "";
  el("saveRename").disabled = false;
  el("saveRename").textContent = "Rename";
  el("renameDialog").showModal();
  el("renameName").focus();
  el("renameName").select();
}

el("closeMoviePicker").onclick = () => el("moviePicker").close();
el("movieSearch").addEventListener("input", renderMovieChoices);
el("closeRename").onclick = () => el("renameDialog").close();
el("cancelRename").onclick = () => el("renameDialog").close();
el("renameForm").addEventListener("submit", async e => {
  e.preventDefault();
  const key = renameKey;
  const newName = el("renameName").value.trim();
  if (!key || !newName) return;
  const save = el("saveRename");
  save.disabled = true;
  save.textContent = "Renaming…";
  el("renameError").textContent = "";
  try {
    await api("/api/rename", { key, newName });
    await refreshMovies();
    el("renameDialog").close();
  } catch (e) {
    el("renameError").textContent = "Rename failed: " + e.message;
  } finally {
    save.disabled = false;
    save.textContent = "Rename";
  }
});

function copyPlay(name, btn) {
  navigator.clipboard.writeText("/pm play <screen> " + name)
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
      const cmd = "/pm play <screen> " + signed.name;
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

// Tabs: the library is fetched when its tab opens (R2 list calls are
// metered); screen status polls regardless since it's local data.
document.querySelectorAll("nav button").forEach(btn => btn.onclick = () => {
  document.querySelectorAll("nav button").forEach(b => b.classList.toggle("active", b === btn));
  document.querySelectorAll("main > section").forEach(s => s.hidden = s.id !== "tab-" + btn.dataset.tab);
  if (btn.dataset.tab === "library") refreshMovies();
  if (btn.dataset.tab === "settings") refreshConfig();
  syncPlayer(false);
});
document.addEventListener("visibilitychange", () => syncPlayer(false));

async function refreshConfig() {
  try {
    const cfg = await api("/api/config");
    el("config").innerHTML = Object.entries(cfg)
      .map(([k, v]) => "<tr><td>" + esc(k) + "</td><td>" + esc(String(v)) + "</td></tr>").join("");
  } catch (e) {
    el("config").innerHTML = '<tr><td class="err">' + esc(e.message) + "</td></tr>";
  }
}
el("reloadCfg").onclick = async () => {
  try { await api("/api/reload"); refreshConfig(); } catch (e) { alert("Reload failed: " + e.message); }
};

el("reload").onclick = refreshMovies;
refreshStatus(); refreshMovies();
setInterval(refreshStatus, 1000);
setInterval(() => {
  const s = selectedScreen();
  if (!s || s.state === "STOPPED" || scrubbing) return;
  const authoritativeSeconds = serverSeconds(s);
  const knownDuration = mediaDurationSeconds(s);
  const displaySeconds = knownDuration ? Math.min(authoritativeSeconds, knownDuration) : authoritativeSeconds;
  timeline.value = Math.min(Number(timeline.max), displaySeconds);
  el("currentTime").textContent = fmtPos(displaySeconds);
}, 250);
</script>
</body>
</html>"""
}
