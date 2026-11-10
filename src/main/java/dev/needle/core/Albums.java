package dev.needle.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Album names for built-in tracks, from the bundled {@code needle/albums.json} (album name to a list of
 * en_us.json keys). The game itself has no album data.
 */
public final class Albums {
	public static final String RESOURCE = "/needle/albums.json";

	private final Map<String, String> byKey;

	private Albums(Map<String, String> byKey) {
		this.byKey = byKey;
	}

	/** Album for an en_us.json key such as {@code music.game.sweden}, or "" when unknown. */
	public String album(String langKey) {
		return byKey.getOrDefault(langKey, "");
	}

	public int size() {
		return byKey.size();
	}

	/** Parses the album file; a key listed under two albums is an error. Keys starting with "_" are comments. */
	public static Albums parse(Reader reader) {
		JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
		Map<String, String> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> album : root.entrySet()) {
			if (album.getKey().startsWith("_")) {
				continue;
			}
			for (JsonElement key : album.getValue().getAsJsonArray()) {
				String previous = out.put(key.getAsString(), album.getKey());
				if (previous != null) {
					throw new IllegalArgumentException(key.getAsString() + " is listed under both " + previous + " and " + album.getKey());
				}
			}
		}
		return new Albums(out);
	}

	/** The bundled table. */
	public static Albums bundled() throws IOException {
		try (InputStream in = Albums.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IOException(RESOURCE + " missing from the jar");
			}
			return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
	}
}
