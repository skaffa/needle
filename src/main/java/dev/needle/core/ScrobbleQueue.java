package dev.needle.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrobbles waiting to be sent, saved in {@code config/needle/queue.json} so they survive restarts and
 * offline play. Every scrobble is saved before it is sent and removed only after Last.fm answered.
 * Not thread safe: the client uses it from its single network thread only.
 */
public final class ScrobbleQueue {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final TypeToken<List<LastFm.Scrobble>> LIST = new TypeToken<>() {
	};

	private final Path file;
	private final List<LastFm.Scrobble> entries = new ArrayList<>();

	private ScrobbleQueue(Path file) {
		this.file = file;
	}

	/** Loads the queue; a missing or unreadable file gives an empty queue (the bad file is kept as .bad). */
	public static ScrobbleQueue load(Path file) {
		ScrobbleQueue q = new ScrobbleQueue(file);
		if (Files.exists(file)) {
			try {
				List<LastFm.Scrobble> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST.getType());
				if (list != null) {
					for (LastFm.Scrobble s : list) {
						if (s != null && s.artist() != null && s.track() != null) {
							q.entries.add(s);
						}
					}
				}
			} catch (IOException | JsonParseException e) {
				try {
					Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
					// keep going with an empty queue
				}
			}
		}
		return q;
	}

	/** Adds and saves. Returns false (and adds nothing) for an exact duplicate. */
	public boolean add(LastFm.Scrobble s) throws IOException {
		if (entries.contains(s)) {
			return false;
		}
		entries.add(s);
		save();
		return true;
	}

	/** Oldest entries first, at most {@link LastFm#MAX_BATCH}. */
	public List<LastFm.Scrobble> nextBatch() {
		return List.copyOf(entries.subList(0, Math.min(entries.size(), LastFm.MAX_BATCH)));
	}

	/** Removes sent (or permanently rejected) entries and saves. */
	public void remove(List<LastFm.Scrobble> sent) throws IOException {
		if (entries.removeAll(sent)) {
			save();
		}
	}

	public int size() {
		return entries.size();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	private void save() throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(entries, LIST.getType()), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Retry delay after failures: 30 s, doubling, at most 30 min. */
	public static final class Backoff {
		static final long FIRST_MS = 30_000;
		static final long MAX_MS = 30 * 60_000;
		private long delayMs;

		/** Records a failure and returns how long to wait before the next try. */
		public long fail() {
			delayMs = delayMs == 0 ? FIRST_MS : Math.min(delayMs * 2, MAX_MS);
			return delayMs;
		}

		public void succeed() {
			delayMs = 0;
		}
	}
}
