# Premiere

An in-world movie theater for **Fabric and Paper servers**. Staff upload a
movie through a drag-and-drop page and play it by name
(`/pm play theater big_buck_bunny`); players who installed the client mod see
the film on an ordinary block wall and hear the soundtrack positionally
through the mod's own audio engine — decoded on their machine from the same
stream as the picture, so lips and voices cannot drift apart.

The server side ships as two interchangeable artifacts speaking the identical
wire protocol: a Fabric mod (which also contains the client half) and a Paper
plugin. Players always use the Fabric client mod regardless of which one the
server runs — a client cannot tell a Paper server from a Fabric one.

**A fully vanilla client connects and plays normally, always.** Premiere
registers no blocks, items, fluids, or block entities, so Fabric's registry
sync has nothing to mismatch on and never kicks anyone. Players without the
mod get the room, the people, and the chat — and a blank wall. That is the
intended degraded experience, not a bug.

## What players need

| Player has | They get |
|---|---|
| Nothing (vanilla) | The theater room, the people, the chat. Blank wall, silence. |
| Premiere on the client | The full theater: sample-locked picture and positional sound. |

Installing the client mod is always optional — a vanilla client is never
kicked and never receives anything it can't handle. The server mod is the
only mandatory piece, and only on the server.

## Setting up the server

The full checklist, in order. Steps 1–2 get you a working theater; steps
3–4 add the movie library (uploads + play-by-name).

1. **Fabric:** drop the Premiere jar in the server's `mods` folder (requires
   Fabric API and Fabric Language Kotlin, like the client). First boot writes
   `config/premiere.json` with every setting blank or defaulted.
   **Paper:** drop `Premiere-Paper` in `plugins`; the config is written to
   `plugins/Premiere/premiere.json` instead (same keys everywhere this README
   says `config/premiere.json`).
2. Optional: grant `movienight.control` to staff. On Fabric, install
   LuckPerms; without it the commands fall back to requiring op level 2. On
   Paper it's a normal Bukkit permission (defaults to op), so any permissions
   plugin works out of the box. `/pm list` is open to everyone.
3. Set up the movie library: a one-time ~15-minute Cloudflare R2 setup, then
   fill in the `r2_*` and `upload_*` fields of `config/premiere.json`.
   Full walkthrough in [Hosting the movie file](#hosting-the-movie-file).
4. Open one TCP port (default **8477**) for the upload page — a
   control-panel question on rented hosts. Only staff ever connect to it.

Without steps 3–4 the mod still works — staff just paste direct URLs into
`/pm play` instead of using names and uploads.

## Hosting the movie file

Movies are never served from the Minecraft server — its connection carries
gameplay, not 15 parallel video streams. Files live in a **fully private**
object-storage bucket (free tier); the mod signs short-lived links for
uploads and playback, and every watching client streams from the bucket
directly.

### The movie library

After the one-time setup below, nobody touches a dashboard or a URL again:

1. `/pm upload` in-game → the mod replies with a **one-time link**
   (valid 30 minutes, single use, gated by the same permission as `play`).
2. Open it, **name the movie** (e.g. `intro_joke`), drop the `.mp4`.
3. Showtime: `/pm play theater intro_joke` — names tab-complete, and
   `/pm movies` lists the library.

The upload page is served by the Minecraft server on a small HTTP port, but
file bytes go **straight from the browser to the bucket** via a presigned
URL — the game server only signs, so gameplay bandwidth is untouched. At
play time the server presigns a **12-hour playback link** and broadcasts it
to viewers; when it expires, the bucket goes back to being unreachable.

Because names are the interface, the library doubles as a pre-roll shelf:
keep small non-copyrighted clips around (community ads, intro jokes,
"silence your phones" bumpers) and play them by name before the feature.

**Subtitles:** players with the client mod toggle them with a keybind
(default **K**) and open the settings screen with another (default **,**) to
pick language, size, and screen height with a live preview — per player,
applied instantly (even mid-film), no config editing. Cues render at
their chosen spot, synced to the master clock, whenever they're
near the playing screen. Two sources, in priority order:

1. A sidecar `.srt` uploaded with the same name as the movie (`bunny.mp4` +
   `bunny.srt`) — paired automatically; `/pm movies` marks such
   films with `(cc)`. For pasted URLs the server checks for an `.srt` next
   to the video.
2. **Text subtitle tracks embedded in the file itself** (SRT/ASS in MKV,
   mov_text in MP4) — streamed out of the same connection as the picture,
   nothing to upload at all. Releases often carry many tracks; the client
   picks the best full track in its preferred language (default `eng`,
   changeable live in the settings screen),
   avoiding "forced" tracks (foreign-dialogue-only) and preferring non-SDH.

The one case needing the sidecar route: Blu-ray-style *bitmap* subtitles
(PGS/VobSub) are images, not text, and are ignored — OCR them to an `.srt`
with Subtitle Edit, or grab one from opensubtitles.org.

**Security model:** the bucket has **no public access at all** — no r2.dev
subdomain, nothing to discover, nothing for strangers to hotlink. Playback
links die on their own within hours. (R2 egress is also free, so even a
leaked link can't run up a bandwidth bill.) The API token lives only in
`config/premiere.json` on the game server and should be **scoped to this one
bucket**: that's the blast radius if the host ever leaks it — movie files,
nothing else. Rotate it in the Cloudflare dashboard if in doubt.

**One-time setup (~15 minutes):**

1. Cloudflare dashboard → **R2** → Create bucket (e.g. `movienight`). Free
   tier: 10 GB storage, unlimited downloads; needs a card on file but nothing
   is charged within limits. **Leave public access off.**
2. In the bucket's *Settings*, add this **CORS policy** so browsers may
   upload through presigned URLs:
   ```json
   [{ "AllowedOrigins": ["*"], "AllowedMethods": ["PUT"],
      "AllowedHeaders": ["*"], "MaxAgeSeconds": 3600 }]
   ```
3. R2 overview → *Manage R2 API Tokens* → **Account API tokens** tab (not
   User — user tokens die if that user ever leaves the account) → create a
   token with **Object Read & Write** scoped to **this one bucket only**.
   Cloudflare then shows an **Access Key ID** and **Secret Access Key** once
   — copy both immediately; those are what go in the config.
4. Fill in `config/premiere.json` on the server:
   ```json
   {
     "upload_http_port": 8477,
     "upload_public_address": "http://your.server.address:8477",
     "r2_account_id": "...",
     "r2_bucket": "movienight",
     "r2_access_key_id": "...",
     "r2_secret_access_key": "..."
   }
   ```
5. Open TCP port 8477 (or your choice) on the host — a control-panel
   question on rented hosts; confirm it before movie night. Then test end
   to end with a small file.

Delete the big files after movie night (R2 dashboard → bucket → Objects);
the free tier is measured in GB. Small pre-roll clips can stay.

### Direct URLs still work

`/pm play <screen> https://...` with any public direct-file URL
bypasses the library entirely — useful for testing or if you host elsewhere:

1. Upload the movie to an object-storage bucket via the provider's dashboard.
   Good free options: **Cloudflare R2** (10 GB free, free egress, needs a
   credit card on file) or **Oracle Cloud Object Storage** (20 GB always
   free). Both support the HTTP range requests seeking needs.
2. Enable the bucket's public HTTPS URL (or a custom domain) once.
3. Copy the file's public link. That's what you paste in-game.

Use **MP4 with H.264 video + AAC audio** for the broadest, hardware-friendly
decode. Delete files after movie night; free tiers are measured in GB.

Avoid Google Drive share links (throttled, unreliable range support) and
YouTube/Twitch URLs (not direct media). If N players watch, the host serves N
parallel streams — both recommended buckets handle that; a home connection or
the Minecraft host itself would not, which is why neither is ever used.

Note on hotlink protection: some hosts reject requests without browser-like
headers. It can look fine in a browser and still fail in the decoder, so test
your URL with the real client pipeline before the event, not just curl.

## Commands

All gated behind `movienight.control` (or op level 2) except `list`.

```
/pm define <screen> [corner1 corner2]        capture a wall (wand selection or coords);
                                             re-run within 30s to overwrite an existing name
/pm wand                                     toggle click-the-corners selection mode
/pm undefine <screen>                        remove a screen
/pm load [screen] <movie|url>                pre-buffer; you get a ping when it's ready
/pm play [screen]                            roll a loaded film (or resume a paused one)
/pm play [screen] <movie|url> [--audio xx]   load and start immediately
/pm pause [screen]                           pause / resume (toggle)
/pm seek [screen] <time>                     1:23:45, 5:30, 90, +30, -1:30
/pm stop [screen]                            stop and clear
/pm volume [screen] <0-100>                  audio volume at the source
/pm upload                                   dashboard link (control panel, library, uploads)
/pm movies                                   list the movie library
/pm reload                                   hot-reload config/premiere.json
/pm list                                     screens and what's playing
```

`/pm`, `/premiere`, and `/movienight` are interchangeable. `[screen]` is
optional everywhere: leave it out and the command targets the nearest screen
in your dimension (or the only one that exists).

`define` takes two opposite corners of a **flat vertical wall** (one block
thick, 2×2 up to 64×64). Stand on the audience side when defining — the side
you're on becomes the front. The wall itself stays ordinary blocks (black
concrete works well); video is fitted inside it preserving aspect ratio, so
the wall doubles as the letterbox. Screen geometry is saved server-side in
`<world>/premiere_screens.json`, independent of the blocks — griefing the
wall doesn't corrupt anything, and the picture renders over whatever is
there.

## Sync model

The server is the master clock: it never touches the media, it just anchors
"position X at moment Y" and broadcasts it (with a refresh every 10s). Each
client decodes video *and* audio from one stream and slaves both to that
clock — video by dropping/waiting per frame, audio as a continuous PCM
stream into the mod's own positional OpenAL source, which stays
sample-locked once aligned. Pause is exact, seeks flush and realign, late
joiners land at the right timestamp.

The one thing software cannot know is the listener's output-device latency
(Bluetooth headphones are 100–300ms by themselves). The **A/V Sync** control
in the client settings (`,` key) trims for it: voices after lips → increase.
Most wired setups need ~0.

Server-side audio settings in `config/premiere.json`: `audio_distance`
(audible radius in blocks, default 48) and `audio_language` (preferred track
for multi-audio films, e.g. "eng"; per-film override: `--audio jpn` on
play/load).

## Tech notes

- Video and audio decode use FFmpeg via JavaCPP, **bundled in the jar** for
  Windows/Linux/macOS x86_64 and macOS arm64 — no VLC or system FFmpeg
  install needed on either the server or clients. (That's why the jar is
  ~100 MB.) Linux arm64 users can add the matching `org.bytedeco` natives to
  the build if needed.
- Pasted URLs are validated on both sides: http/https only, public addresses
  only. The server never opens the media at all — its entire network
  footprint is listing and signing against the operator's bucket. All
  decoding happens on the watching clients.
- Whatever you stream is subject to normal copyright and platform terms;
  the mod does nothing to change that.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| One player sees a blank wall | They don't have the Premiere client mod (expected!), or they're in a different dimension than the screen. |
| *Everyone* sees a blank wall | Bad source: check the client log for `Rejecting broadcast URL` or `Video decode failed`. For pasted URLs, open it in a browser — it must be a direct file, no page in front. |
| Picture but no sound | Check the client log for "movie audio unavailable"; also the film may simply have no audio track, or the player is outside `audio_distance` blocks. |
| Sound noticeably after the picture | That player's output-device latency (Bluetooth!): raise their A/V Sync in the settings screen (`,` key) until lips match. |
| `Uploads aren't configured` | One of the `r2_*` fields in `config/premiere.json` is blank. |
| Upload link prints but the page won't load | The upload TCP port isn't open/forwarded on the host, or `upload_public_address` points to the wrong address. |
| Upload page loads but the transfer fails | Usually CORS: add the policy from the setup steps to the bucket. Check the browser console to confirm. |
| `Could not reach the movie library` | Typo in `r2_account_id`/`r2_bucket`, or the API token lacks read access to the bucket. Test after fixing — no restart needed. |
| Playback of a library movie fails for everyone | If the upload worked, suspect the playback link: links expire after 12h (run `play` again for a fresh one), and the API token needs *read* as well as write. |
| Video out of sync after a lag spike | Self-corrects within ~10s; past 2.5s drift it hard-seeks. Don't replay to fix it. |
| `No screen named ...` | `/pm list` shows defined screens; names are case-sensitive single words. |

Also worth knowing: `play` always starts from the beginning (pause is the
only mid-film control), and reaching the end of a movie holds the last frame
until `stop` clears it.

## Development

Three Gradle modules:

- `common/` — pure-JVM shared core: screen registry, playback clock, wire
  codecs (raw netty), upload/dashboard, R2. Zero `net.minecraft`/loader
  imports (Fabric remaps to intermediary at runtime, Paper runs mojmap — MC
  references here would break one side or the other).
- `fabric/` — the mod: client (video/audio/subtitles) + a thin server
  adapter over the common core. This is the only MC-version-sensitive module.
- `paper/` — the plugin: Bukkit-API-only adapter over the same core, shadow-
  jarred with kotlin-stdlib.

The `fabric` module is versioned by [Stonecutter](https://stonecutter.kikugie.dev/):
one subproject per MC version (`fabric/versions/<v>/`), sources shared with
version-gated comments. Adding an MC version later: append it to `versions()`
in `settings.gradle.kts`, create `fabric/versions/<v>/gradle.properties`
(minecraft_version + fabric_version), append to `modrinth_versions` in
`gradle.properties`, and gate divergent code with stonecutter comments.
`common` and `paper` are deliberately version-free.

Commands:

- `./gradlew assembleAll` builds every Fabric version jar plus the Paper
  plugin jar in lockstep (`fabric/versions/<v>/build/libs`,
  `paper/build/libs`). Ship artifacts from the same release: the wire format
  carries a version byte and mismatches fail loud.
- `./gradlew :fabric:26.2:runServer` starts a Fabric dev server;
  `./gradlew :fabric:26.2:runClient` starts a dev client that auto-joins
  `127.0.0.1:25565`. All versions share the `fabric/run` harness.
- `./gradlew :paper:runPaper` rebuilds the plugin and starts the Paper dev
  server (one-time: put a Paper jar at `paper/run/paper.jar` from the PaperMC
  downloads API and set `eula=true`). The same dev client connects to it —
  which is exactly the wire-compatibility test.

## Releasing

One-time setup:

1. Create the Modrinth project (in its settings mark **both client and server
   supported** — the Fabric jar carries both halves) and put its project ID in
   `modrinth_id` in `gradle.properties`. Create the CurseForge project and put
   the numeric ID in `curseforge_id`. Until the IDs are set, the publish step
   dry-runs harmlessly.
2. Add `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN` secrets to the GitHub repo.

Per release:

1. Add a `## Premiere - vX.Y.Z` section at the top of `CHANGELOG.md`.
2. Set `mod_version=vX.Y.Z` in `gradle.properties` (use `-beta.N`/`-alpha.N`
   suffixes for prereleases — they publish as such everywhere).
3. Commit, then `git tag vX.Y.Z && git push --tags`.

The release workflow verifies the tag matches `mod_version`, builds all
artifacts, creates a GitHub Release with every jar and the changelog section,
and publishes: each Fabric version jar (`vX.Y.Z+<mc>`) to Modrinth and
CurseForge, and the Paper jar (`vX.Y.Z+paper`) to the same Modrinth project.
Local publish check: `./gradlew :fabric:chiseledPublishMods :paper:publishMods`
(dry-runs without tokens; always use this pair, never bare `publishMods`).

## License

GPLv3. See [LICENSE.txt](LICENSE.txt).
