# Changelog

## Premiere - v1.0.0-beta.1

First public beta of Premiere: an in-world movie theater for Minecraft 26.2.

- Fabric mod (required on the server or the client): video on ordinary block
  walls, positional audio decoded from the same stream, subtitles (sidecar
  `.srt` or embedded tracks), a drag-and-drop upload dashboard backed by
  Cloudflare R2, and staff commands (`/pm`).
- Paper plugin: the complete server half over the Bukkit API — wire-compatible
  with the same Fabric client mod, so server owners can run Paper instead of
  Fabric with zero player-side difference.
- Polished staff feedback on both server types with consistent status colors,
  clearer wording, click-to-run movie commands, and a clickable private
  dashboard link. LAN links are detected automatically; remote deployments can
  use their configured HTTPS proxy or tunnel URL.
- Truly positional screen audio (spatial stereo when supported, mono fallback),
  with a persistent per-screen full-volume audience zone, a configurable
  server default, and smooth falloff to the outer audible radius.
- Per-screen playback queues in commands and the dashboard pre-buffer each
  movie before automatically rolling it, and portrait dashboard previews
  remain fully visible. Direct movie selection now always loads first.
- Fully vanilla clients are never kicked and never receive anything they can't
  handle; they just see the wall.
