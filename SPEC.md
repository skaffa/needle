# Needle — spec

Client-side Fabric mod for Minecraft 26.3 that scrobbles the game's own music and jukebox songs to Last.fm.
Only built-in (vanilla) tracks are scrobbled. Works in singleplayer and on any server; sends no game packets.

- Mod id `needle`, package `dev.needle`, command `/needle`.
- Build setup copied from Gauge (Loom, JDK 25, split client/main source sets, JUnit for core).
- `src/main` (core) never imports Minecraft classes, so the tracking and Last.fm logic is unit tested.

## 1. What 26.3 gives us (checked against the 26.3 jars)

| Need | Source in 26.3 |
| --- | --- |
| Current game music | `MusicManager.currentMusic` (`SoundInstance`), set in `startPlaying(Music)`, cleared in `stopPlaying()` |
| Exact track | `SoundInstance.getSound().getLocation()`, e.g. `minecraft:music/game/sweden` |
| Track name | `Identifier.toShortLanguageKey()` gives `music.game.sweden`; `en_us.json` has `"C418 - Sweden"`. The game's own "Now Playing" toast uses this. All 71 music files in 26.3 have such a key. |
| Jukebox start / stop | `LevelEventHandler.playJukeboxSong(Holder<JukeboxSong>, BlockPos)` and `stopJukeboxSong(BlockPos)` (private; Gauge already injects at TAIL / HEAD) |
| Jukebox sound instance | `LevelEventHandler.playingJukeboxSongs` (`Map<BlockPos, SoundInstance>`) |
| Jukebox track name | `jukebox_song.minecraft.<id>` in `en_us.json`, e.g. `"C418 - cat"`; 21 vanilla discs |
| Jukebox length | `JukeboxSong.lengthInSeconds()` |
| Jukebox audible range | `SimpleSoundInstance.forJukeboxSong`: volume 4.0, `LINEAR` attenuation, 16-block base = 64 blocks |
| Is it still playing | `SoundManager.isActive(SoundInstance)` |
| Did it come from vanilla | `ResourceManager.getResource(sound.getPath())` → `Resource.sourcePackId()` is `"vanilla"` |

Names are always read from the vanilla `en_us.json`, never from the player's selected language,
so scrobbles are consistent. The value is split on the first `" - "` into artist and title.

## 2. Track identity and the vanilla filter

A sound is a **track** when all of these hold:

1. Game music: the instance's `SoundSource` is `MUSIC` and its sound file is under `minecraft:music/`.
   Jukebox: the song's registry key is in the `minecraft` namespace.
2. `en_us.json` has a name for it (`music.…` or `jukebox_song.minecraft.…`).
3. The sound file resolves from the vanilla pack (`sourcePackId() == "vanilla"`). A resource pack that
   replaces `records/cat.ogg` with other music is therefore not scrobbled as "C418 - cat".

Anything else is ignored and logged once at debug level (`needle: skipped <id> (<reason>)`).
The track id is the sound file path (`music/game/sweden`, `records/cat`); it is stable across events
(`ebb` plays in both menu and game music).

Custom tracks (data-pack discs, music packs) are out of scope for v1. Planned for v2: a config map from
sound path or disc id to `{artist, title, album}` that skips rule 3 for listed entries.

## 3. Durations

- Jukebox: `lengthInSeconds()`.
- Game music: read from the `.ogg` once per track and cache it: sample rate from the Vorbis identification
  header, total samples from the granule position of the last Ogg page. Pure core function, unit tested.
- Unknown or unreadable length: the track is not scrobbled (Last.fm needs the 30 s minimum check), but
  Now Playing is still sent.

## 4. Listening rules

**Threshold** (Last.fm rule, made configurable):
`required = min(fraction × length, maxRequiredMinutes × 60 s)`; defaults `fraction = 0.5`,
`maxRequiredMinutes = 4`. Tracks shorter than 30 s are never scrobbled.

**Audible** means, for the current tick:

- The sound instance is active (`SoundManager.isActive`).
- The Master slider is above 0% (`requireMasterVolume`), and the track's own slider is above 0%:
  Music for game and menu music (`requireMusicVolume`), Jukebox/Note Blocks for jukeboxes
  (`requireJukeboxVolume`). All three checks default on; each can be switched off in the config.
- Jukebox only: the game is not paused (`Minecraft.isPaused()`; singleplayer pauses records but not music),
  the player is in the same dimension, and within `listenRadius` blocks (default 64, the vanilla audible
  distance; config range 1–64).

Time is measured in real milliseconds (`System.nanoTime` deltas between client ticks), not ticks, so lag
does not change the result. A single gap over 2 s is capped at 2 s (frozen game, debugger).

## 5. Several sources: the focus model

Each playing track has a **listen** record: `trackId, artist, title, length, startedAt (epoch s),
listenedMs, instances (set of SoundInstance), scrobbled flag, nowPlayingSent flag`.

Every tick exactly one listen has **focus**, and only that listen gains time:

1. Among audible jukebox listens, the one whose nearest instance is closest to the player.
2. Else the game music listen, if audible.
3. Else none.

Rules:

- **Exclusive time.** The sum of all counters can never exceed real time, so two songs cannot both be
  scrobbled for the same minutes.
- **Resume.** Walking out of range and back keeps the counter as long as the same instance is still playing.
- **Same disc, two jukeboxes.** Instances with the same track id share one listen. `startedAt` is the
  earliest start. The listen ends when its last instance ends.
- **Scrobble at threshold.** When `listenedMs >= required`, the listen is queued for scrobbling
  immediately with `timestamp = startedAt`. It is never scrobbled twice.
- **End.** A listen ends when all its instances are gone: stop event, `isActive` false, dimension change,
  disconnect, or (jukebox) `startedAt + length + 5 s` has passed. Unscrobbled listens are dropped.
- **Late join.** A jukebox that started before the player came near never sends a start event, and vanilla
  does not play it to the player either. It is not tracked. This matches what the player hears.
- **Replay.** The same disc inserted again is a new listen and may scrobble again.

**Now Playing**: sent with `track.updateNowPlaying` when a listen has held focus for 5 s continuously and
has not been announced yet, or when focus returns to it after another track was announced. At most one
call per 15 s. Disabled by `sendNowPlaying = false`.

## 6. Last.fm

- Endpoint `https://ws.audioscrobbler.com/2.0/`, `POST`, `format=json`, `java.net.http.HttpClient`, all
  calls on one background thread, never on the render or tick thread.
- Signature: md5 of the parameters sorted by name, concatenated as `namevalue`, plus the secret
  (`format` and `callback` excluded). Core function with a known-answer unit test.
- **Login** (`/needle login`): `auth.getToken` → chat shows a clickable link to
  `https://www.last.fm/api/auth/?api_key=…&token=…` → poll `auth.getSession` every 5 s for up to 5 min →
  store the session key and user name. `/needle logout` deletes them.
- **Scrobble**: `track.scrobble` with `artist, track, album, timestamp, duration`
  (`chosenByUser = 0` for game music, `1` for jukeboxes). Now Playing sends `album` too.
- **Albums**: the game has no album data, so `src/main/resources/needle/albums.json` maps each en_us key to
  an album. Names match the Last.fm album pages with the most listeners (checked 2026-11-10, e.g.
  "Minecraft - Volume Alpha", "Minecraft: Caves & Cliffs (Original Game Soundtrack)"). 90 of the 92 tracks
  in 26.3 are listed; Boss and Lava Chicken have no clear release and are sent without an album.
- **Queue**: every scrobble is first appended to `config/needle/queue.json`, then sent in batches of up to
  50. Removed only on success. Retry with backoff (30 s doubling to 30 min) on network errors and on
  Last.fm errors 11, 16, 29. Errors 9 (invalid session) stops sending and tells the player to log in again.
  Ignored scrobbles (accepted=0 with an ignoredMessage) are removed and logged. Queue survives restarts.
- **API key and secret**: injected at build time from Gradle properties `lastfm.apiKey` and
  `lastfm.apiSecret` (set in `~/.gradle/gradle.properties`, never committed) into a generated class.
  The config may override them (`apiKey`, `apiSecret`) for users who build without them or want their own.
  Shipping the secret in the jar is normal for desktop scrobblers; it only identifies the app.

## 7. Files

Everything lives in the mod's own folder, `config/needle/`:

- `config.json`: user settings (below). Safe to share.
- `session.json`: Last.fm session key and user name. Never logged.
- `queue.json`: pending scrobbles.

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch (also `/needle pause` / `/needle resume`) |
| `fraction` | `0.5` | Part of the track that must be heard (0.1–1.0) |
| `maxRequiredMinutes` | `4` | Cap on required time (0 = no cap) |
| `listenRadius` | `64` | Jukebox listening distance in blocks (1–64) |
| `requireMasterVolume` | `true` | Nothing counts while Master is at 0% |
| `requireMusicVolume` | `true` | Music counts only while the Music slider is above 0% |
| `requireJukeboxVolume` | `true` | Jukeboxes count only while Jukebox/Note Blocks is above 0% |
| `scrobbleGameMusic` | `true` | In-world music (overworld, nether, end, water, creative, credits) |
| `scrobbleMenuMusic` | `true` | Music while no world is loaded |
| `scrobbleJukebox` | `true` | Jukebox songs |
| `sendNowPlaying` | `true` | Send `track.updateNowPlaying` |
| `showHud` | `false` | Small HUD line with focus track and progress |
| `apiKey`, `apiSecret` | empty | Override the built-in key |

Menu vs game music is decided by whether `Minecraft.level` is null when the track starts.

## 8. Commands and HUD

- `/needle status`: login, enabled, queue size, focus listen with `listened / required`, other listens.
- `/needle login`, `/needle logout`, `/needle pause`, `/needle resume`, `/needle flush` (send queue now),
  `/needle reload` (re-read config.json).
- Settings screen with every config option and the Last.fm login state: Mod Menu "Configure" button, or
  `/needle config`. Changes apply and save immediately.
- HUD (when `showHud`): one line top-left, e.g. `♪ C418 - Sweden  1:12 / 2:00`; `✓` once scrobbled;
  grey when the focus listen is not gaining time.
- Chat message on each scrobble is off by default (`chatOnScrobble`, add if wanted).

## 9. Code layout

Core (`src/main/java/dev/needle/core`, no Minecraft imports, JUnit tested):

- `ListenTracker`: listens, focus selection, time accumulation, threshold, end detection. Input per tick:
  a list of `Source(trackId, meta, instanceKey, kind, audible, distance)` and `nowNanos`. Output: events
  (`NowPlaying`, `Scrobble`).
- `Threshold`: required-time math.
- `TrackName`: `"Artist - Title"` split.
- `OggDuration`: length from Ogg/Vorbis bytes.
- `LastFmSigner`, `LastFmRequests`: parameter building and signing.
- `ScrobbleQueue`: JSON persistence and batching.
- `NeedleConfig`.

Client (`src/client/java/dev/needle/client`):

- `NeedleClient`: entry point, END_CLIENT_TICK builds sources and feeds `ListenTracker`.
- `mixin/LevelEventHandlerMixin` (start/stop) and `LevelEventHandlerAccessor` (`playingJukeboxSongs`).
- `mixin/MusicManagerAccessor` (`currentMusic`).
- `VanillaTracks`: en_us lookup, vanilla-pack check, duration cache (cleared on resource reload).
- `LastFmClient`: HTTP worker thread, login polling, queue sending.
- `NeedleCommands`, `NeedleHud`.

## 10. Tests

- JUnit: focus switching between two jukeboxes, resume after leaving range, same disc on two jukeboxes,
  exclusive time sum, threshold cap, short-track rule, menu toggle, Ogg duration on a small fixture file,
  signature known answer, queue round trip.
- In game (dev client, singleplayer test world): game music scrobble at reduced `fraction`, two jukeboxes
  32 blocks apart with different discs while walking between them, pause menu, muting `RECORDS`,
  resource pack replacing a disc (must not scrobble), offline queue then reconnect.
  A test Last.fm account is needed for the end-to-end check.

- Scripted in-game scenarios (`./run-scenario.sh <name>`, see `DevTest`): `1` two jukeboxes, `rules` pause /
  mute / Now Playing / dimension change, `pack` resource-pack replacement, `offline` queue and retry,
  `screen` settings screen, `server` walk-away and late join on a dedicated server (`./gradlew runServer`,
  run-server/ with op NeedleTester). All passed 2026-11-10. Finding: in 26.3 the server sends a stop
  event when the player leaves, so the song-length end is only a fallback.

## 11. Out of scope for v1

Custom tracks (v2 config map), note blocks, `/playsound`,
music from other mods.
