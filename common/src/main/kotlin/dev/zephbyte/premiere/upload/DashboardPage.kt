package dev.zephbyte.premiere.upload

import com.google.gson.JsonObject

/**
 * The dashboard's HTML, kept out of the server code: one self-contained page
 * (no external assets), one session token baked in.
 */
object DashboardPage {

    val EXPIRED = """
        <!doctype html><meta charset="utf-8"><title>Link expired</title>
        <body style="font-family:system-ui;background:#14161a;color:#e8e6e3;display:grid;place-items:center;min-height:100vh">
        <p>This dashboard link is invalid or expired. Run <b>/pm upload</b> in-game for a fresh one.</p>
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
  main { width: min(760px, 94vw); display: grid; gap: 1rem; }
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
    <table><thead><tr><th>Screen</th><th>Status</th><th>Now showing</th><th class="num">Position</th><th></th></tr></thead>
    <tbody id="screens"><tr><td colspan="5" class="hint">Loading…</td></tr></tbody></table>
    <p class="hint">Wall size and volume live in <b>/pm list</b> and <b>/pm volume</b>; everything else is right here.</p>
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
<script>
const TOKEN = ${JsonObject().also { it.addProperty("t", token) }["t"]};
const el = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

async function api(path, body = {}) {
  const res = await fetch(path, { method: "POST", body: JSON.stringify({ token: TOKEN, ...body }) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (res.status === 403) { el("banner").style.display = "block";
      el("banner").textContent = data.error || "Session expired; run /pm upload again."; }
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
      ? '<tr><td colspan="5" class="hint">No screens defined. Use /pm wand + /pm define in-game.</td></tr>'
      : screens.map(s => {
        const active = s.state !== "STOPPED";
        const toggleLabel = s.state === "PLAYING" ? "&#9208;" : "&#9205;"; // pause / play
        return '<tr><td>' + esc(s.name) + '<br><span class="hint">' + esc(s.size) + ' ' + esc(s.facing) + '</span></td>' +
        '<td class="state ' + esc(s.state) + '">' + esc(s.state.toLowerCase()) +
          (active ? '<br><span class="hint">vol ' + s.volumePercent + '%</span>' : '') + '</td>' +
        '<td>' + (s.label ? esc(s.label) : '<span class="hint">—</span>') + '</td>' +
        '<td class="num">' + (active ? fmtPos(s.positionSeconds) : '—') + '</td>' +
        '<td class="actions">' +
          (active
            ? '<button class="ghost" title="Play/Pause" onclick="ctl(\'' + esc(s.name) + '\',\'toggle\')">' + toggleLabel + '</button>' +
              '<button class="ghost" title="Back 30s" onclick="seekScreen(\'' + esc(s.name) + '\',\'-30\')">&#9194;</button>' +
              '<button class="ghost" title="Forward 30s" onclick="seekScreen(\'' + esc(s.name) + '\',\'+30\')">&#9193;</button>' +
              '<button class="ghost" title="Jump to time" onclick="seekTo(\'' + esc(s.name) + '\')">&#8981;</button>' +
              '<button class="ghost" title="Stop" onclick="ctl(\'' + esc(s.name) + '\',\'stop\')">&#9209;</button>'
            : '') +
          '<button class="ghost" title="Play a movie here" onclick="playOn(\'' + esc(s.name) + '\')">&#127909;</button>' +
          '<button class="danger" title="Delete this screen" onclick="delScreen(\'' + esc(s.name) + '\')">&#10005;</button>' +
        '</td></tr>';
      }).join("");
  } catch (e) { /* banner already shown on 403 */ }
}

async function ctl(screen, action) {
  try { await api("/api/screen/control", { screen, action }); refreshStatus(); }
  catch (e) { alert(e.message); }
}
async function seekScreen(screen, time) {
  try { await api("/api/screen/seek", { screen, time }); refreshStatus(); }
  catch (e) { alert(e.message); }
}
function seekTo(screen) {
  const time = prompt("Jump to (1:23:45, 5:30, 90, +30, -30):");
  if (time) seekScreen(screen, time);
}
function playOn(screen) {
  const movie = prompt("Movie name (or URL) to play on '" + screen + "':");
  if (!movie) return;
  api("/api/screen/play", { screen, movie }).then(refreshStatus).catch(e => alert(e.message));
}
function delScreen(screen) {
  if (!confirm("Delete screen '" + screen + "'? This stops any playback and removes its definition.")) return;
  ctl(screen, "delete");
}

async function refreshMovies() {
  try {
    const { movies } = await api("/api/list");
    el("movies").innerHTML = movies.length === 0
      ? '<tr><td colspan="6" class="hint">Library is empty — upload something below.</td></tr>'
      : movies.map(m => {
        const isSub = m.key.toLowerCase().endsWith(".srt");
        return '<tr><td>' + esc(m.name) + (isSub ? ' <span class="hint">(subtitles)</span>' : '') + '</td>' +
        '<td class="num">' + fmtSize(m.size) + '</td>' +
        '<td>' + esc((m.lastModified || "").slice(0, 10)) + '</td>' +
        '<td>' + (isSub ? '' : '<button class="ghost" onclick="copyPlay(\'' + esc(m.name) + '\', this)">Copy play command</button>') + '</td>' +
        '<td><button class="ghost" onclick="rename(\'' + esc(m.key) + '\', \'' + esc(m.name) + '\')">Rename</button></td>' +
        '<td><button class="danger" onclick="del(\'' + esc(m.key) + '\')">Delete</button></td></tr>';
      }).join("");
  } catch (e) {
    el("movies").innerHTML = '<tr><td colspan="6" class="err">' + esc(e.message) + '</td></tr>';
  }
}

async function rename(key, currentName) {
  const newName = prompt("New name for '" + currentName + "':", currentName);
  if (!newName || newName === currentName) return;
  try { await api("/api/rename", { key, newName }); refreshMovies(); }
  catch (e) { alert("Rename failed: " + e.message); }
}

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
});

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
setInterval(refreshStatus, 5000);
</script>
</body>
</html>"""
}
