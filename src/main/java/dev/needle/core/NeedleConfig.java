package dev.needle.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Player settings, saved in {@code config/needle/config.json} (SPEC.md section 7). Safe to share: the
 * Last.fm login lives in {@code session.json} next to it.
 */
public final class NeedleConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Every file Needle writes, all inside its own folder in the game's config folder. */
	public record Locations(Path dir) {
		public static Locations in(Path gameConfigDir) {
			return new Locations(gameConfigDir.resolve("needle"));
		}

		public Path config() {
			return dir.resolve("config.json");
		}

		public Path session() {
			return dir.resolve("session.json");
		}

		public Path queue() {
			return dir.resolve("queue.json");
		}
	}

	public static final int MAX_LISTEN_RADIUS = 64;

	/** Master switch; {@code /needle pause} and {@code /needle resume} flip it. */
	public boolean enabled = true;
	/** Part of the track that must be heard, 0.1-1.0. */
	public double fraction = 0.5;
	/** Cap on the required listening time in minutes; 0 = no cap. */
	public int maxRequiredMinutes = 4;
	/** Jukebox listening distance in blocks, 1-64 (64 is where vanilla jukeboxes go silent). */
	public int listenRadius = MAX_LISTEN_RADIUS;
	/** Nothing counts while the Master slider is at 0%. */
	public boolean requireMasterVolume = true;
	/** Game and menu music count only while the Music slider is above 0%. */
	public boolean requireMusicVolume = true;
	/** Jukebox songs count only while the Jukebox/Note Blocks slider is above 0%. */
	public boolean requireJukeboxVolume = true;
	public boolean scrobbleGameMusic = true;
	public boolean scrobbleMenuMusic = true;
	public boolean scrobbleJukebox = true;
	public boolean sendNowPlaying = true;
	public boolean showHud = false;
	public boolean chatOnScrobble = false;
	/** Override for the built-in Last.fm API key; empty = use the built-in one. */
	public String apiKey = "";
	public String apiSecret = "";

	public NeedleConfig clamp() {
		if (!(fraction >= 0.1 && fraction <= 1.0)) {
			fraction = Double.isNaN(fraction) ? 0.5 : Math.clamp(fraction, 0.1, 1.0);
		}
		maxRequiredMinutes = Math.max(0, maxRequiredMinutes);
		listenRadius = Math.clamp(listenRadius, 1, MAX_LISTEN_RADIUS);
		if (apiKey == null) {
			apiKey = "";
		}
		if (apiSecret == null) {
			apiSecret = "";
		}
		return this;
	}

	/**
	 * Whether the volume sliders allow counting. Sliders are 0.0-1.0 as in the game options; a check that
	 * is switched off always passes.
	 */
	public boolean volumeAllows(double master, double category, boolean jukebox) {
		if (requireMasterVolume && master <= 0) {
			return false;
		}
		boolean requireCategory = jukebox ? requireJukeboxVolume : requireMusicVolume;
		return !requireCategory || category > 0;
	}

	public Threshold threshold() {
		return new Threshold(fraction, maxRequiredMinutes);
	}

	/** The API key in use: the config override when both parts are set, else the built-in one. */
	public String effectiveApiKey() {
		return hasOverride() ? apiKey.strip() : BuiltInKeys.API_KEY;
	}

	public String effectiveApiSecret() {
		return hasOverride() ? apiSecret.strip() : BuiltInKeys.API_SECRET;
	}

	public boolean hasApiKey() {
		return !effectiveApiKey().isEmpty() && !effectiveApiSecret().isEmpty();
	}

	private boolean hasOverride() {
		return !apiKey.isBlank() && !apiSecret.isBlank();
	}

	public static NeedleConfig load(Path file) {
		if (!Files.exists(file)) {
			return new NeedleConfig();
		}
		try {
			NeedleConfig c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), NeedleConfig.class);
			return c == null ? new NeedleConfig() : c.clamp();
		} catch (IOException | JsonParseException e) {
			return new NeedleConfig();
		}
	}

	public void save(Path file) throws IOException {
		writeAtomic(file, GSON.toJson(this));
	}

	// ---- session (config/needle/session.json, kept apart so the config can be shared) ----

	public static LastFm.Session loadSession(Path file) {
		if (!Files.exists(file)) {
			return null;
		}
		try {
			LastFm.Session s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LastFm.Session.class);
			return s == null || s.key() == null || s.key().isBlank() ? null : s;
		} catch (IOException | JsonParseException e) {
			return null;
		}
	}

	public static void saveSession(Path file, LastFm.Session session) throws IOException {
		if (session == null) {
			Files.deleteIfExists(file);
		} else {
			writeAtomic(file, GSON.toJson(session));
		}
	}

	private static void writeAtomic(Path file, String text) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
