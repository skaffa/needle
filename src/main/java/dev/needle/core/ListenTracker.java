package dev.needle.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The focus model from SPEC.md section 5. Each tick the client reports every playing track instance
 * ({@link Source}); instances of the same track share one {@link Listen}. Exactly one listen has focus
 * per tick and only that one gains listening time, so the counters can never add up to more than real
 * time. Pure logic: no Minecraft classes, no clock of its own.
 */
public final class ListenTracker {
	/** Where a sound comes from. Jukeboxes beat music for focus; music is never chosen by the player. */
	public enum Kind {
		MENU_MUSIC, GAME_MUSIC, JUKEBOX
	}

	/** A built-in track. {@code lengthMs <= 0} means unknown (Now Playing only, never scrobbled). */
	public record Track(String id, TrackName name, long lengthMs) {
	}

	/**
	 * One playing sound instance this tick.
	 *
	 * @param audible         loud enough, not paused, in range (the client decides; see SPEC.md section 4)
	 * @param distance        blocks from the player to a jukebox; ignored for music
	 * @param startedAtEpochS when this instance started, in Unix seconds (the scrobble timestamp)
	 */
	public record Source(Track track, Kind kind, boolean audible, double distance, long startedAtEpochS) {
	}

	public sealed interface Event {
	}

	/** Send {@code track.updateNowPlaying}. */
	public record NowPlaying(Track track, Kind kind) implements Event {
	}

	/** Queue a {@code track.scrobble}. */
	public record Scrobble(Track track, Kind kind, long timestampEpochS) implements Event {
	}

	/** Read-only view for status output and the HUD. */
	public record ListenView(Track track, Kind kind, long listenedMs, long requiredMs, boolean scrobbled,
			boolean focused, boolean audible, int instances) {
	}

	/** Longest single gap counted between two ticks, so a frozen game does not count as listening. */
	public static final long MAX_TICK_GAP_MS = 2_000;
	/** Focus must be held this long before Now Playing is sent. */
	public static final long NOW_PLAYING_AFTER_MS = 5_000;
	/** At most one Now Playing call per this interval. */
	public static final long NOW_PLAYING_MIN_INTERVAL_MS = 15_000;

	private static final class Listen {
		final Track track;
		Kind kind;
		long startedAtEpochS;
		long listenedNanos;
		boolean scrobbled;
		boolean audible;
		int instances;
		double nearest;

		Listen(Track track) {
			this.track = track;
		}
	}

	private final Map<String, Listen> listens = new LinkedHashMap<>();
	private Threshold threshold;
	private Long lastTickNanos;
	private Listen focus;
	private long focusSinceNanos;
	private Listen announced;
	private Long lastNowPlayingNanos;

	public ListenTracker(Threshold threshold) {
		this.threshold = threshold;
	}

	public void setThreshold(Threshold threshold) {
		this.threshold = threshold;
	}

	public Threshold threshold() {
		return threshold;
	}

	/**
	 * Advances to {@code nowNanos} with the instances playing right now. A listen whose track has no
	 * instance in {@code sources} has ended and is dropped (scrobbled or not).
	 */
	public List<Event> tick(long nowNanos, List<Source> sources) {
		List<Event> events = new ArrayList<>();
		long gap = lastTickNanos == null ? 0 : Math.max(0, nowNanos - lastTickNanos);
		gap = Math.min(gap, MAX_TICK_GAP_MS * 1_000_000L);
		lastTickNanos = nowNanos;

		// Group this tick's instances by track.
		Map<String, Listen> seen = new LinkedHashMap<>();
		for (Source s : sources) {
			Listen l = seen.get(s.track().id());
			if (l == null) {
				l = listens.get(s.track().id());
				if (l == null) {
					l = new Listen(s.track());
					l.startedAtEpochS = s.startedAtEpochS();
				}
				l.audible = false;
				l.instances = 0;
				l.nearest = Double.POSITIVE_INFINITY;
				seen.put(s.track().id(), l);
			}
			l.instances++;
			l.startedAtEpochS = Math.min(l.startedAtEpochS, s.startedAtEpochS());
			// A jukebox instance makes the whole listen a jukebox listen (chosen by the player).
			if (l.kind == null || s.kind() == Kind.JUKEBOX) {
				l.kind = s.kind();
			}
			if (s.audible()) {
				l.audible = true;
				if (s.kind() == Kind.JUKEBOX) {
					l.nearest = Math.min(l.nearest, s.distance());
				}
			}
		}
		listens.clear();
		listens.putAll(seen);

		// Focus: nearest audible jukebox, else audible music, else nothing.
		Listen next = null;
		for (Listen l : listens.values()) {
			if (!l.audible) {
				continue;
			}
			if (l.kind == Kind.JUKEBOX) {
				if (next == null || next.kind != Kind.JUKEBOX || l.nearest < next.nearest) {
					next = l;
				}
			} else if (next == null) {
				next = l;
			}
		}
		if (next != focus) {
			focus = next;
			focusSinceNanos = nowNanos;
		}
		if (!listens.containsValue(announced)) {
			announced = null;
		}

		if (focus != null) {
			focus.listenedNanos += gap;
			long required = threshold.requiredMs(focus.track.lengthMs());
			if (!focus.scrobbled && required >= 0 && focus.listenedNanos >= required * 1_000_000L) {
				focus.scrobbled = true;
				events.add(new Scrobble(focus.track, focus.kind, focus.startedAtEpochS));
			}
			boolean heldLongEnough = nowNanos - focusSinceNanos >= NOW_PLAYING_AFTER_MS * 1_000_000L;
			boolean rateOk = lastNowPlayingNanos == null
					|| nowNanos - lastNowPlayingNanos >= NOW_PLAYING_MIN_INTERVAL_MS * 1_000_000L;
			if (focus != announced && heldLongEnough && rateOk) {
				announced = focus;
				lastNowPlayingNanos = nowNanos;
				events.add(new NowPlaying(focus.track, focus.kind));
			}
		}
		return events;
	}

	/** Drops everything (disconnect, dimension change, scrobbling switched off). */
	public void reset() {
		listens.clear();
		focus = null;
		announced = null;
		lastTickNanos = null;
	}

	public List<ListenView> listens() {
		List<ListenView> out = new ArrayList<>(listens.size());
		for (Listen l : listens.values()) {
			out.add(new ListenView(l.track, l.kind, l.listenedNanos / 1_000_000L,
					threshold.requiredMs(l.track.lengthMs()), l.scrobbled, l == focus, l.audible, l.instances));
		}
		return Collections.unmodifiableList(out);
	}

	/** The listen that gained time on the last tick, or null. */
	public ListenView focus() {
		for (ListenView v : listens()) {
			if (v.focused()) {
				return v;
			}
		}
		return null;
	}
}
