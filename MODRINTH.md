**Scrobble Minecraft's own soundtrack to Last.fm.** Needle counts the game music and the music discs you
play in jukeboxes, and only the time you actually hear them. Client-side: works in singleplayer and on any
server, no server mod needed.

## Getting started

1. Install [Fabric API](https://modrinth.com/mod/fabric-api). [Mod Menu](https://modrinth.com/mod/modmenu) is optional.
2. Join a world and run `/needle login`. Click the link and allow access on last.fm.
3. Play. Each track is scrobbled once you've heard half of it (at most 4 minutes), just like Last.fm's own rule.

## Only what you hear

- **Game music** (title screen, overworld, nether, end, underwater, creative, credits) counts while it plays and your Master and Music sliders are above 0%.
- **Jukeboxes** count while you're within 64 blocks, the game isn't paused, and the Jukebox/Note Blocks slider is above 0%.
- **One track at a time.** When several things play, the nearest jukebox you can hear gets the time. Walk away and come back while the song still plays, and the count picks up where it left off.
- A jukebox that started before you arrived doesn't count: the game doesn't play it to you either.

## Built-in music only

All 92 tracks in Minecraft 26.3: C418, Lena Raine, Kumi Tanioka, Aaron Cherof, Amos Roddy, fingerspit,
Samuel Åberg and more. Names come from the game itself, and 90 tracks are scrobbled with their album
(Minecraft - Volume Alpha, Volume Beta, the Caves & Cliffs, Trails & Tales and Tricky Trials soundtracks, ...).
Data-pack discs, music from other mods and tracks replaced by a resource pack are skipped, so nothing ends
up on your profile under the wrong name.

## Also

- **Now Playing** on your Last.fm profile while you listen.
- **Offline queue.** Scrobbles wait while you're offline and are sent later.
- **Settings screen** (Mod Menu → Configure, or `/needle config`): required part of a track, the 4-minute cap, jukebox range, which sources count, the volume checks, an optional HUD line and chat messages.
- `/needle status` shows what is being counted and how far along it is.

## Privacy

Needle connects to Last.fm (`ws.audioscrobbler.com`) and nothing else, and only after you log in. It
sends the artist, title, album, start time and length of tracks it scrobbles or shows as Now Playing.
No world, server, chat or player data, no telemetry. Your login is stored in `config/needle/session.json`;
`/needle logout` deletes it.

---

Requires Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API and Java 25. MIT licensed.
