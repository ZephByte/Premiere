# Premiere

An in-world movie theater for Fabric servers. Staff paste a URL, players who
installed the client mod see the film on an ordinary block wall, and
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

1. Drop the Premiere jar in the server's `mods` folder (requires Fabric API
   and Fabric Language Kotlin, like the client).
2. Install Simple Voice Chat server-side if you want movie audio. Make sure
   SVC's UDP port (default **24454**) is open — on a rented host this is a
   control-panel or support-ticket question. Confirm it *before* movie night.
3. Optional: install LuckPerms and grant `movienight.control` to staff.
   Without LuckPerms, the commands fall back to requiring op level 2.
   `/movienight list` is open to everyone.

## Hosting the movie file

The mod never uploads, downloads, or stores the film, and holds no storage
credentials; it relays a public URL, and every watching client streams it
directly. Hosting is done by hand, outside the mod, on infrastructure you
control:

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
/movienight play <screen> <url>                   start a film from the beginning
/movienight pause <screen>                        pause / resume (toggle)
/movienight stop <screen>                         stop and clear
/movienight volume <screen> <0-100>               audio volume at the source
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
- URLs are validated on both sides: http/https only, public addresses only.
  The server's only network touch is a plain GET of the same public URL the
  clients read.
- Whatever you stream is subject to normal copyright and platform terms;
  the mod does nothing to change that.

## Development

- `./gradlew build` produces the jar in `build/libs`.
- `./gradlew runServer` starts a dev server; `./gradlew runClient` starts a
  dev client that auto-joins `127.0.0.1:25565`.
- Drop the Simple Voice Chat fabric jar into `run/mods` to test audio in dev.

## License

GPLv3. See [LICENSE.txt](LICENSE.txt).
