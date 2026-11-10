package dev.needle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.needle.core.Albums;
import dev.needle.core.ListenTracker;
import dev.needle.core.OggDuration;
import dev.needle.core.TrackName;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.JukeboxSong;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a playing sound is a built-in track (SPEC.md section 2) and builds its {@link
 * ListenTracker.Track}: name from the vanilla {@code en_us.json}, length from the song definition or the
 * {@code .ogg} file. Main thread only, except the Ogg reads, which run in the background.
 */
final class VanillaTracks {
	static final String VANILLA_PACK = "vanilla";

	/** "Artist - Title" for every {@code music.*} and {@code jukebox_song.*} key in the vanilla en_us.json. */
	private static Map<String, String> names;
	/** Per resolved sound; {@link Sound} objects are rebuilt on resource reload, so this clears itself. */
	private static final Map<Sound, Optional<ListenTracker.Track>> RESOLVED = new WeakHashMap<>();
	/** Lengths of vanilla music files by sound path. Vanilla files never change, so this is never cleared. */
	private static final Map<Identifier, CompletableFuture<Long>> LENGTHS = new ConcurrentHashMap<>();
	private static final Set<String> LOGGED = new HashSet<>();
	private static Albums albums;

	private VanillaTracks() {
	}

	/** The game music track, or null when it is not a built-in track (or its length is still being read). */
	static ListenTracker.Track music(Minecraft mc, SoundInstance instance) {
		if (instance.getSource() != SoundSource.MUSIC) {
			return null;
		}
		Sound sound = instance.getSound();
		if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
			return null;
		}
		Optional<ListenTracker.Track> known = RESOLVED.get(sound);
		if (known != null) {
			return known.orElse(null);
		}
		Identifier location = sound.getLocation();
		if (!location.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) || !location.getPath().startsWith("music/")) {
			return remember(sound, null, "not a minecraft music file");
		}
		// same key the vanilla Now Playing toast uses: music/game/sweden -> music.game.sweden
		String langKey = location.getPath().replace('/', '.');
		TrackName name = TrackName.parse(names(mc).get(langKey));
		if (name == null) {
			return remember(sound, null, "no en_us name");
		}
		Resource resource = vanillaResource(mc, sound);
		if (resource == null) {
			return remember(sound, null, "file replaced by a resource pack");
		}
		CompletableFuture<Long> length = LENGTHS.computeIfAbsent(sound.getPath(), path -> CompletableFuture.supplyAsync(() -> {
			try (InputStream in = resource.open()) {
				return OggDuration.lengthMs(in);
			} catch (Exception e) {
				NeedleClient.LOG.warn("needle: could not read length of {}", path, e);
				return -1L;
			}
		}));
		if (!length.isDone()) {
			return null; // try again next tick; nothing is cached yet
		}
		return remember(sound, new ListenTracker.Track(location.getPath(), name, length.join(), album(langKey)), null);
	}

	/** The jukebox song as a track, or null when it is not a built-in disc. */
	static ListenTracker.Track jukebox(Minecraft mc, Holder<JukeboxSong> song, SoundInstance instance) {
		Sound sound = instance.getSound();
		if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
			return null;
		}
		Optional<ListenTracker.Track> known = RESOLVED.get(sound);
		if (known != null) {
			return known.orElse(null);
		}
		Optional<ResourceKey<JukeboxSong>> key = song.unwrapKey();
		if (key.isEmpty() || !key.get().identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
			return remember(sound, null, "not a minecraft disc");
		}
		String langKey = "jukebox_song.minecraft." + key.get().identifier().getPath();
		TrackName name = TrackName.parse(names(mc).get(langKey));
		if (name == null) {
			return remember(sound, null, "no en_us name");
		}
		if (vanillaResource(mc, sound) == null) {
			return remember(sound, null, "file replaced by a resource pack");
		}
		long lengthMs = (long) (song.value().lengthInSeconds() * 1000);
		return remember(sound, new ListenTracker.Track(sound.getLocation().getPath(), name, lengthMs, album(langKey)), null);
	}

	private static String album(String langKey) {
		if (albums == null) {
			try {
				albums = Albums.bundled();
			} catch (Exception e) {
				NeedleClient.LOG.error("needle: could not read the album table; scrobbling without albums", e);
				albums = Albums.parse(new java.io.StringReader("{}"));
			}
		}
		return albums.album(langKey);
	}

	/** The sound file as the game will play it, only when it comes from the vanilla pack. */
	private static Resource vanillaResource(Minecraft mc, Sound sound) {
		Optional<Resource> top = mc.getResourceManager().getResource(sound.getPath());
		return top.isPresent() && VANILLA_PACK.equals(top.get().sourcePackId()) ? top.get() : null;
	}

	private static ListenTracker.Track remember(Sound sound, ListenTracker.Track track, String skipReason) {
		RESOLVED.put(sound, Optional.ofNullable(track));
		if (track == null && LOGGED.add(sound.getLocation() + skipReason)) {
			NeedleClient.LOG.info("needle: skipped {} ({})", sound.getLocation(), skipReason);
		}
		return track;
	}

	private static Map<String, String> names(Minecraft mc) {
		if (names != null) {
			return names;
		}
		Map<String, String> out = new HashMap<>();
		for (Resource r : mc.getResourceManager().getResourceStack(Identifier.withDefaultNamespace("lang/en_us.json"))) {
			if (!VANILLA_PACK.equals(r.sourcePackId())) {
				continue;
			}
			try (Reader reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
				JsonObject lang = JsonParser.parseReader(reader).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : lang.entrySet()) {
					if (e.getKey().startsWith("music.") || e.getKey().startsWith("jukebox_song.")) {
						out.put(e.getKey(), e.getValue().getAsString());
					}
				}
			} catch (Exception e) {
				NeedleClient.LOG.error("needle: could not read the vanilla en_us.json", e);
			}
		}
		if (out.isEmpty()) {
			return out; // resources not loaded yet: try again later
		}
		NeedleClient.LOG.info("needle: {} track names from the vanilla en_us.json", out.size());
		names = out;
		return names;
	}
}
