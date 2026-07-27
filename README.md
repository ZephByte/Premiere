# Premiere

An in-world movie theater for Fabric servers. Staff upload a movie through a
drag-and-drop page and play it by name (`/movienight play theater
big_buck_bunny`); players who installed the client mod see the film on an
ordinary block wall, and
[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) carries the
soundtrack as positional audio that falls off with distance from the screen.

**A fully vanilla client connects and plays normally, always.** Premiere
registers no blocks, items, fluids, or block entities, so Fabric's registry
sync has nothing to mismatch on and never kicks anyone. Players without the
mod get the room, the people, and the chat — and a blank wall. That is the
intended degraded experience, not a bug.

## What players need

| Player has | They get |
|---|---|
| Nothing (vanilla) | The theater room, the people, the chat. Blank wall, silence. |
| Simple Voice Chat only | Positional movie audio. Blank wall for the picture. |
| SVC + Premiere on the client | The full theater: synced picture and positional sound. |

Installing anything is always optional. The server mod is the only mandatory
piece, and only on the server.

## Setting up the server

The full checklist, in order. Steps 1–3 get you picture and sound; steps 4–5
add the movie library (uploads + play-by-name).

1. Drop the Premiere jar in the server's `mods` folder (requires Fabric API
   and Fabric Language Kotlin, like the client). First boot writes
   `config/premiere.json` with every setting blank or defaulted.
2. Install Simple Voice Chat server-side if you want movie audio. Make sure
   SVC's UDP port (default **24454**) is open — on a rented host this is a
   control-panel or support-ticket question. Confirm it *before* movie night.
3. Optional: install LuckPerms and grant `movienight.control` to staff.
   Without LuckPerms, the commands fall back to requiring op level 2.
   `/movienight list` is open to everyone.
4. Set up the movie library: a one-time ~15-minute Cloudflare R2 setup, then
   fill in the `r2_*` and `upload_*` fields of `config/premiere.json`.
   Full walkthrough in [Hosting the movie file](#hosting-the-movie-file).
5. Open one TCP port (default **8477**) for the upload page, same drill as
   the SVC port. Only staff ever connect to it.

Without steps 4–5 the mod still works — staff just paste direct URLs into
`/movienight play` instead of using names and uploads.

## Hosting the movie file

Movies are never served from the Minecraft server — its connection carries
gameplay, not 15 parallel video streams. Files live in a **fully private**
object-storage bucket (free tier); the mod signs short-lived links for
uploads and playback, and every watching client streams from the bucket
directly.

### The movie library

After the one-time setup below, nobody touches a dashboard or a URL again:

1. `/movienight upload` in-game → the mod replies with a **one-time link**
   (valid 30 minutes, single use, gated by the same permission as `play`).
2. Open it, **name the movie** (e.g. `intro_joke`), drop the `.mp4`.
3. Showtime: `/movienight play theater intro_joke` — names tab-complete, and
   `/movienight movies` lists the library.

The upload page is served by the Minecraft server on a small HTTP port, but
file bytes go **straight from the browser to the bucket** via a presigned
URL — the game server only signs, so gameplay bandwidth is untouched. At
play time the server presigns a **12-hour playback link** and broadcasts it
to viewers; when it expires, the bucket goes back to being unreachable.

Because names are the interface, the library doubles as a pre-roll shelf:
keep small non-copyrighted clips around (community ads, intro jokes,
"silence your phones" bumpers) and play them by name before the feature.

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
5. Open TCP port 8477 (or your choice) on the host — same control-panel
   drill as Simple Voice Chat's UDP port, and the same caveat: confirm it
   before movie night. Then test end to end with a small file.

Delete the big files after movie night (R2 dashboard → bucket → Objects);
the free tier is measured in GB. Small pre-roll clips can stay.

### Direct URLs still work

`/movienight play <screen> https://...` with any public direct-file URL
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
/movienight define <screen> <corner1> <corner2>   capture a wall as a screen
/movienight undefine <screen>                     remove a screen
/movienight play <screen> <movie|url>             start from the beginning (names tab-complete)
/movienight pause <screen>                        pause / resume (toggle)
/movienight stop <screen>                         stop and clear
/movienight volume <screen> <0-100>               audio volume at the source
/movienight upload                                one-time browser link to add a movie
/movienight movies                                list the movie library
/movienight list                                  screens and what's playing
```

`define` takes two opposite corners of a **flat vertical wall** (one block
thick, 2×2 up to 64×64). Stand on the audience side when defining — the side
you're on becomes the front. The wall itself stays ordinary blocks (black
concrete works well); video is fitted inside it preserving aspect ratio, so
the wall doubles as the letterbox. Screen geometry is saved server-side in
`<world>/premiere_screens.json`, independent of the blocks — griefing the
wall doesn't corrupt anything, and the picture renders over whatever is
there.

## Sync model

The server is the master clock. It decodes the audio track itself (one
authoritative timeline for everyone in the room) and broadcasts the media
position to video clients, who chase it: small drift is tolerated, sustained
drift over ~2.5s hard-seeks. Late joiners request the current state on join
and land at the right timestamp. Pause, resume, and seek all flow from the
same server-side playback record.

Audio is fed to Simple Voice Chat slightly *ahead* of the clock to offset
SVC's encode/transport/buffer latency. Tune it in `config/premiere.json`:

```json
{
  "audio_lead_ms": 150,   // sound arrives after the picture -> increase
  "audio_distance": 48.0  // audible radius of a screen, in blocks
}
```

Watch something with a clean visual beat (a clap, a bounce) and adjust in
±50ms steps until it feels right for your server's network; restart (or
stop/play) to apply.

## Tech notes

- Video and audio decode use FFmpeg via JavaCPP, **bundled in the jar** for
  Windows/Linux/macOS x86_64 and macOS arm64 — no VLC or system FFmpeg
  install needed on either the server or clients. (That's why the jar is
  ~100 MB.) Linux arm64 users can add the matching `org.bytedeco` natives to
  the build if needed.
- Pasted URLs are validated on both sides: http/https only, public addresses
  only. The server's network footprint is: decoding the audio track from the
  same link the clients read, plus (library mode) listing and signing against
  the operator's bucket. Video bytes never pass through it.
- Whatever you stream is subject to normal copyright and platform terms;
  the mod does nothing to change that.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| One player sees a blank wall | They don't have the Premiere client mod (expected!), or they're in a different dimension than the screen. |
| *Everyone* sees a blank wall | Bad source: check the client log for `Rejecting broadcast URL` or `Video decode failed`. For pasted URLs, open it in a browser — it must be a direct file, no page in front. |
| Picture but no sound | Player is missing the SVC *client* mod, SVC's UDP port is closed on the host (SVC shows a crossed-out mic icon), or the "Movies" category is turned down in SVC's volume settings (`V` key). |
| Sound but no picture | Player has SVC but not Premiere — that's the middle tier, working as designed. |
| Sound noticeably after the picture | Raise `audio_lead_ms` in `config/premiere.json` in ~50ms steps (lower it if sound leads). Stop/play to apply. |
| `Uploads aren't configured` | One of the `r2_*` fields in `config/premiere.json` is blank. |
| Upload link prints but the page won't load | The upload TCP port isn't open/forwarded on the host, or `upload_public_address` points to the wrong address. |
| Upload page loads but the transfer fails | Usually CORS: add the policy from the setup steps to the bucket. Check the browser console to confirm. |
| `Could not reach the movie library` | Typo in `r2_account_id`/`r2_bucket`, or the API token lacks read access to the bucket. Test after fixing — no restart needed. |
| Playback of a library movie fails for everyone | If the upload worked, suspect the playback link: links expire after 12h (run `play` again for a fresh one), and the API token needs *read* as well as write. |
| Video out of sync after a lag spike | Self-corrects within ~10s; past 2.5s drift it hard-seeks. Don't replay to fix it. |
| `No screen named ...` | `/movienight list` shows defined screens; names are case-sensitive single words. |

Also worth knowing: `play` always starts from the beginning (pause is the
only mid-film control), and reaching the end of a movie holds the last frame
until `stop` clears it.

## Development

- `./gradlew build` produces the jar in `build/libs`.
- `./gradlew runServer` starts a dev server; `./gradlew runClient` starts a
  dev client that auto-joins `127.0.0.1:25565`.
- Drop the Simple Voice Chat fabric jar into `run/mods` to test audio in dev.

## License

GPLv3. See [LICENSE.txt](LICENSE.txt).
