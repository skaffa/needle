package dev.needle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.needle.core.ListenTracker.Event;
import dev.needle.core.ListenTracker.Kind;
import dev.needle.core.ListenTracker.ListenView;
import dev.needle.core.ListenTracker.NowPlaying;
import dev.needle.core.ListenTracker.Scrobble;
import dev.needle.core.ListenTracker.Source;
import dev.needle.core.ListenTracker.Track;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ListenTrackerTest {
	static final Track CAT = new Track("records/cat", new TrackName("C418", "cat"), 185_000);
	static final Track FAR = new Track("records/far", new TrackName("C418", "far"), 174_000);
	static final Track SWEDEN = new Track("music/game/sweden", new TrackName("C418", "Sweden"), 215_000);
	static final Track SHORT = new Track("records/11", new TrackName("C418", "11"), 25_000);

	/** Drives the tracker in 50 ms ticks (20 TPS) and collects all events. */
	static final class Clock {
		final ListenTracker tracker;
		long nanos = 1_000_000_000L;
		final List<Event> events = new ArrayList<>();

		Clock(Threshold t) {
			tracker = new ListenTracker(t);
		}

		void run(long ms, Source... sources) {
			for (long t = 0; t < ms; t += 50) {
				nanos += 50_000_000L;
				events.addAll(tracker.tick(nanos, List.of(sources)));
			}
		}

		List<Scrobble> scrobbles() {
			return events.stream().filter(e -> e instanceof Scrobble).map(e -> (Scrobble) e).toList();
		}

		List<NowPlaying> nowPlaying() {
			return events.stream().filter(e -> e instanceof NowPlaying).map(e -> (NowPlaying) e).toList();
		}

		ListenView view(Track t) {
			return tracker.listens().stream().filter(v -> v.track().equals(t)).findFirst().orElse(null);
		}
	}

	static Source jukebox(Track t, double distance, boolean audible) {
		return new Source(t, Kind.JUKEBOX, audible, distance, 1000);
	}

	static Source music(Track t, boolean audible) {
		return new Source(t, Kind.GAME_MUSIC, audible, 0, 2000);
	}

	@Test
	void thresholdRules() {
		assertEquals(92_500, Threshold.LAST_FM.requiredMs(185_000));
		assertEquals(240_000, Threshold.LAST_FM.requiredMs(600_000), "capped at 4 minutes");
		assertEquals(600_000, new Threshold(1.0, 0).requiredMs(600_000), "0 = no cap");
		assertEquals(-1, Threshold.LAST_FM.requiredMs(30_000), "30 s or shorter never scrobbles");
		assertEquals(-1, Threshold.LAST_FM.requiredMs(0), "unknown length");
	}

	@Test
	void trackNameSplitsOnFirstSeparator() {
		assertEquals(new TrackName("Lena Raine", "Creator (Music Box)"), TrackName.parse("Lena Raine - Creator (Music Box)"));
		assertEquals(new TrackName("A", "B - C"), TrackName.parse("A - B - C"));
		assertNull(TrackName.parse("NoSeparator"));
		assertNull(TrackName.parse(" - Title"));
	}

	@Test
	void scrobblesOnceAtThresholdWithStartTimestamp() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(92_000, jukebox(CAT, 5, true));
		assertTrue(c.scrobbles().isEmpty());
		c.run(1_000, jukebox(CAT, 5, true));
		assertEquals(List.of(new Scrobble(CAT, Kind.JUKEBOX, 1000)), c.scrobbles());
		c.run(60_000, jukebox(CAT, 5, true));
		assertEquals(1, c.scrobbles().size(), "never twice");
	}

	@Test
	void timeOnlyCountsWhileAudible() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(50_000, jukebox(CAT, 80, false));
		assertEquals(0, c.view(CAT).listenedMs());
		c.run(10_000, jukebox(CAT, 10, true));
		assertEquals(10_000, c.view(CAT).listenedMs(), 100);
	}

	@Test
	void resumeAfterLeavingRange() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(60_000, jukebox(CAT, 10, true));
		c.run(30_000, jukebox(CAT, 90, false));
		c.run(33_000, jukebox(CAT, 10, true));
		assertEquals(1, c.scrobbles().size());
	}

	@Test
	void nearestJukeboxHasFocusAndTimeIsExclusive() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(40_000, jukebox(CAT, 10, true), jukebox(FAR, 30, true));
		c.run(40_000, jukebox(CAT, 30, true), jukebox(FAR, 10, true));
		assertEquals(40_000, c.view(CAT).listenedMs(), 100);
		assertEquals(40_000, c.view(FAR).listenedMs(), 100);
		assertTrue(c.scrobbles().isEmpty(), "80 s total cannot satisfy either track");
		c.run(60_000, jukebox(CAT, 10, true), jukebox(FAR, 30, true));
		assertEquals(List.of(CAT), c.scrobbles().stream().map(Scrobble::track).toList());
	}

	@Test
	void jukeboxBeatsGameMusic() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(20_000, jukebox(CAT, 60, true), music(SWEDEN, true));
		assertEquals(20_000, c.view(CAT).listenedMs(), 100);
		assertEquals(0, c.view(SWEDEN).listenedMs());
		c.run(20_000, jukebox(CAT, 70, false), music(SWEDEN, true));
		assertEquals(20_000, c.view(SWEDEN).listenedMs(), 100);
	}

	@Test
	void sameDiscOnTwoJukeboxesIsOneListen() {
		Clock c = new Clock(Threshold.LAST_FM);
		Source a = new Source(CAT, Kind.JUKEBOX, true, 10, 1000);
		Source b = new Source(CAT, Kind.JUKEBOX, true, 12, 990);
		c.run(100_000, a, b);
		assertEquals(1, c.tracker.listens().size());
		assertEquals(2, c.view(CAT).instances());
		assertEquals(List.of(new Scrobble(CAT, Kind.JUKEBOX, 990)), c.scrobbles(), "earliest start");
	}

	@Test
	void endedListenIsDroppedAndReplayCountsAgain() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(50_000, jukebox(CAT, 5, true));
		c.run(50);
		assertNull(c.view(CAT));
		c.run(50_000, jukebox(CAT, 5, true));
		assertTrue(c.scrobbles().isEmpty(), "progress from the first play is gone");
		c.run(43_000, jukebox(CAT, 5, true));
		assertEquals(1, c.scrobbles().size());
	}

	@Test
	void shortAndUnknownTracksNeverScrobble() {
		Clock c = new Clock(Threshold.LAST_FM);
		Track unknown = new Track("music/game/new", new TrackName("X", "Y"), 0);
		c.run(30_000, jukebox(SHORT, 5, true));
		c.run(300_000, music(unknown, true));
		assertTrue(c.scrobbles().isEmpty());
		assertEquals(2, c.nowPlaying().size(), "Now Playing still sent");
	}

	@Test
	void frozenGameGapIsCapped() {
		ListenTracker t = new ListenTracker(Threshold.LAST_FM);
		List<Source> s = List.of(jukebox(CAT, 5, true));
		t.tick(0, s);
		t.tick(60_000_000_000L, s);
		assertEquals(ListenTracker.MAX_TICK_GAP_MS, t.focus().listenedMs());
	}

	@Test
	void nowPlayingAfterFiveSecondsAndRateLimited() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(4_900, jukebox(CAT, 5, true));
		assertTrue(c.nowPlaying().isEmpty());
		c.run(200, jukebox(CAT, 5, true));
		assertEquals(List.of(new NowPlaying(CAT, Kind.JUKEBOX)), c.nowPlaying());
		// Switch to FAR: held 5 s, but only ~5 s since the last call, so it waits for the 15 s limit.
		c.run(6_000, jukebox(CAT, 30, true), jukebox(FAR, 5, true));
		assertEquals(1, c.nowPlaying().size());
		c.run(10_000, jukebox(CAT, 30, true), jukebox(FAR, 5, true));
		assertEquals(2, c.nowPlaying().size());
		assertEquals(FAR, c.nowPlaying().get(1).track());
		// A short flick back to CAT and back to FAR does not resend FAR.
		c.run(1_000, jukebox(CAT, 5, true), jukebox(FAR, 30, true));
		c.run(30_000, jukebox(CAT, 30, true), jukebox(FAR, 5, true));
		assertEquals(2, c.nowPlaying().size());
	}

	@Test
	void resetDropsEverything() {
		Clock c = new Clock(Threshold.LAST_FM);
		c.run(10_000, jukebox(CAT, 5, true));
		c.tracker.reset();
		assertTrue(c.tracker.listens().isEmpty());
		assertNull(c.tracker.focus());
		c.run(5_000, jukebox(CAT, 5, true));
		assertFalse(c.view(CAT).scrobbled());
		assertEquals(4_950, c.view(CAT).listenedMs(), "first tick after reset counts no gap");
	}
}
