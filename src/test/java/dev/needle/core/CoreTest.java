package dev.needle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class CoreTest {
	// ---- Ogg ----

	/** Minimal Ogg page: header, one lacing value, body. CRC left zero (not checked). */
	static byte[] page(int serial, long granule, byte[] body) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII));
		out.write(0); // version
		out.write(0); // header type
		for (int i = 0; i < 8; i++) {
			out.write((int) (granule >>> (8 * i)) & 0xFF);
		}
		for (int i = 0; i < 4; i++) {
			out.write((serial >>> (8 * i)) & 0xFF);
		}
		out.writeBytes(new byte[8]); // sequence + crc
		out.write(1);
		out.write(body.length);
		out.writeBytes(body);
		return out.toByteArray();
	}

	static byte[] vorbisIdent(int rate) {
		byte[] b = new byte[30];
		b[0] = 1;
		System.arraycopy("vorbis".getBytes(StandardCharsets.US_ASCII), 0, b, 1, 6);
		b[11] = 2; // channels
		for (int i = 0; i < 4; i++) {
			b[12 + i] = (byte) (rate >>> (8 * i));
		}
		return b;
	}

	static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}

	@Test
	void oggLengthFromLastGranule() {
		byte[] ogg = concat(page(7, 0, vorbisIdent(44_100)), page(7, 44_100L * 90, new byte[40]),
				page(7, 44_100L * 215 + 22_050, new byte[10]));
		assertEquals(215_500, OggDuration.lengthMs(ogg));
	}

	@Test
	void oggIgnoresOtherStreamsAndUnsetGranules() {
		byte[] ogg = concat(page(7, 0, vorbisIdent(48_000)), page(7, 48_000L * 60, new byte[5]),
				page(9, 48_000L * 999, new byte[5]), page(7, -1L, new byte[5]));
		assertEquals(60_000, OggDuration.lengthMs(ogg));
	}

	@Test
	void oggRejectsGarbage() {
		assertEquals(-1, OggDuration.lengthMs(new byte[0]));
		assertEquals(-1, OggDuration.lengthMs("not an ogg file at all, just text".getBytes(StandardCharsets.UTF_8)));
		assertEquals(-1, OggDuration.lengthMs(page(1, 100, new byte[30])), "no vorbis header");
	}

	/** Real game file from Loom's asset cache, when present (skipped elsewhere). */
	@Test
	void oggRealSweden() throws IOException {
		Path assets = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "assets");
		Path index = null;
		if (Files.isDirectory(assets.resolve("indexes"))) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(assets.resolve("indexes"), "26.3*.json")) {
				for (Path p : ds) {
					index = p;
				}
			}
		}
		assumeTrue(index != null, "no 26.3 asset index in the Gradle cache");
		JsonObject objects = JsonParser.parseString(Files.readString(index)).getAsJsonObject().getAsJsonObject("objects");
		String hash = objects.getAsJsonObject("minecraft/sounds/music/game/sweden.ogg").get("hash").getAsString();
		Path ogg = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
		assumeTrue(Files.exists(ogg), "sweden.ogg not downloaded");
		long ms = OggDuration.lengthMs(Files.readAllBytes(ogg));
		// Sweden is about 3:35
		assertTrue(ms > 200_000 && ms < 230_000, "length " + ms);
	}

	// ---- Last.fm ----

	@Test
	void signatureKnownAnswers() {
		Map<String, String> p = new TreeMap<>(Map.of("method", "auth.getSession", "token", "TOK", "api_key", "KEY",
				"format", "json", "callback", "x"));
		assertEquals("0bf279e021f3a81b4553dd7e76cf72ad", LastFm.signature(p, "SECRET"), "format/callback excluded");
		Map<String, String> utf = Map.of("method", "track.scrobble", "sk", "", "api_key", "KEY",
				"artist[0]", "Lena Raine", "track[0]", "Créateur");
		assertEquals("ed1088893da47b593bc805272001f152", LastFm.signature(utf, "SECRET"), "UTF-8 bytes");
	}

	@Test
	void signedRequestsHaveKeySigAndFormat() {
		LastFm.Request r = LastFm.getSession("KEY", "SECRET", "TOK");
		assertEquals("0bf279e021f3a81b4553dd7e76cf72ad", r.params().get("api_sig"));
		assertEquals("json", r.params().get("format"));
		assertEquals("auth.getSession", r.method());
		assertTrue(LastFm.formBody(r).contains("token=TOK"));
	}

	@Test
	void scrobbleBatchParams() {
		List<LastFm.Scrobble> batch = List.of(new LastFm.Scrobble("C418", "Sweden", 100, 215, false),
				new LastFm.Scrobble("C418", "cat & dog", 400, 0, true));
		LastFm.Request r = LastFm.scrobble("KEY", "SECRET", "SK", batch);
		Map<String, String> p = r.params();
		assertEquals("Sweden", p.get("track[0]"));
		assertEquals("0", p.get("chosenByUser[0]"));
		assertEquals("215", p.get("duration[0]"));
		assertEquals("1", p.get("chosenByUser[1]"));
		assertNull(p.get("duration[1]"), "unknown duration omitted");
		assertEquals("SK", p.get("sk"));
		assertTrue(LastFm.formBody(r).contains("track%5B1%5D=cat+%26+dog"));
		assertThrows(IllegalArgumentException.class, () -> LastFm.scrobble("K", "S", "SK", List.of()));
	}

	@Test
	void albumSentWhenKnown() {
		LastFm.Request r = LastFm.scrobble("K", "S", "SK", List.of(
				new LastFm.Scrobble("C418", "Sweden", 100, 215, false, "Minecraft - Volume Alpha"),
				new LastFm.Scrobble("C418", "Boss", 200, 200, false, ""),
				new LastFm.Scrobble("C418", "cat", 300, 185, true)));
		assertEquals("Minecraft - Volume Alpha", r.params().get("album[0]"));
		assertNull(r.params().get("album[1]"));
		assertNull(r.params().get("album[2]"), "old queue entries have no album");
		LastFm.Request np = LastFm.updateNowPlaying("K", "S", "SK", "C418", "Sweden", "Minecraft - Volume Alpha", 215);
		assertEquals("Minecraft - Volume Alpha", np.params().get("album"));
		assertNull(LastFm.updateNowPlaying("K", "S", "SK", "C418", "Boss", "", 0).params().get("album"));
	}

	@Test
	void bundledAlbumTable() throws IOException {
		Albums a = Albums.bundled();
		assertEquals(90, a.size(), "92 vanilla tracks in 26.3, minus Boss and Lava Chicken");
		assertEquals("Minecraft - Volume Alpha", a.album("music.game.sweden"));
		assertEquals("Minecraft - Volume Beta", a.album("jukebox_song.minecraft.far"));
		assertEquals("Minecraft: Wild Update (Original Game Soundtrack)", a.album("jukebox_song.minecraft.5"));
		assertEquals("", a.album("music.game.end.boss"));
		assertThrows(IllegalArgumentException.class,
				() -> Albums.parse(new java.io.StringReader("{\"A\":[\"x\"],\"B\":[\"x\"]}")), "one key, two albums");
	}

	@Test
	void parsesResponses() {
		assertEquals("abc", LastFm.token("{\"token\":\"abc\"}"));
		assertEquals(new LastFm.Session("skaffa", "k1"), LastFm.session("{\"session\":{\"name\":\"skaffa\",\"key\":\"k1\",\"subscriber\":0}}"));
		assertEquals(new LastFm.ApiError(14, "Unauthorized Token"), LastFm.error("{\"error\":14,\"message\":\"Unauthorized Token\"}"));
		assertNull(LastFm.error("{\"token\":\"abc\"}"));
		assertNull(LastFm.error("<html>bad gateway</html>"));

		String one = "{\"scrobbles\":{\"scrobble\":{\"track\":{\"#text\":\"Sweden\"},\"ignoredMessage\":{\"code\":\"0\",\"#text\":\"\"}},"
				+ "\"@attr\":{\"accepted\":1,\"ignored\":0}}}";
		assertEquals(List.of(new LastFm.ScrobbleResult(true, "0", "")), LastFm.scrobbleResults(one));
		String two = "{\"scrobbles\":{\"scrobble\":[{\"ignoredMessage\":{\"code\":\"0\",\"#text\":\"\"}},"
				+ "{\"ignoredMessage\":{\"code\":\"3\",\"#text\":\"Timestamp too old\"}}],\"@attr\":{\"accepted\":1,\"ignored\":1}}}";
		List<LastFm.ScrobbleResult> r = LastFm.scrobbleResults(two);
		assertEquals(2, r.size());
		assertFalse(r.get(1).accepted());
		assertEquals("Timestamp too old", r.get(1).ignoredMessage());
	}

	@Test
	void errorActions() {
		assertEquals(LastFm.ErrorAction.RETRY, LastFm.actionFor(29));
		assertEquals(LastFm.ErrorAction.RETRY, LastFm.actionFor(14));
		assertEquals(LastFm.ErrorAction.RELOGIN, LastFm.actionFor(9));
		assertEquals(LastFm.ErrorAction.BAD_KEY, LastFm.actionFor(10));
		assertEquals(LastFm.ErrorAction.DROP, LastFm.actionFor(6));
	}

	// ---- queue ----

	@Test
	void queueRoundTripBatchesAndDedupes(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("needle").resolve("queue.json");
		ScrobbleQueue q = ScrobbleQueue.load(file);
		assertTrue(q.isEmpty());
		List<LastFm.Scrobble> all = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			LastFm.Scrobble s = new LastFm.Scrobble("C418", "Track " + i, 1000 + i, 200, i % 2 == 0);
			all.add(s);
			assertTrue(q.add(s));
		}
		assertFalse(q.add(all.get(3)), "duplicate");

		ScrobbleQueue reloaded = ScrobbleQueue.load(file);
		assertEquals(60, reloaded.size());
		List<LastFm.Scrobble> batch = reloaded.nextBatch();
		assertEquals(50, batch.size());
		assertEquals(all.get(0), batch.get(0));
		reloaded.remove(batch);
		assertEquals(all.subList(50, 60), ScrobbleQueue.load(file).nextBatch());
	}

	@Test
	void corruptQueueIsSetAside(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("queue.json");
		Files.writeString(file, "{not json");
		assertTrue(ScrobbleQueue.load(file).isEmpty());
		assertTrue(Files.exists(dir.resolve("queue.json.bad")));
	}

	@Test
	void backoffDoublesToCap() {
		ScrobbleQueue.Backoff b = new ScrobbleQueue.Backoff();
		assertEquals(30_000, b.fail());
		assertEquals(60_000, b.fail());
		for (int i = 0; i < 10; i++) {
			b.fail();
		}
		assertEquals(30 * 60_000, b.fail());
		b.succeed();
		assertEquals(30_000, b.fail());
	}

	// ---- config ----

	@Test
	void configClampsAndRoundTrips(@TempDir Path dir) throws IOException {
		Path file = NeedleConfig.Locations.in(dir).config();
		assertEquals(dir.resolve("needle").resolve("config.json"), file);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"fraction\": 5, \"listenRadius\": 500, \"maxRequiredMinutes\": -3, \"apiKey\": null}");
		NeedleConfig c = NeedleConfig.load(file);
		assertEquals(1.0, c.fraction);
		assertEquals(64, c.listenRadius);
		assertEquals(0, c.maxRequiredMinutes);
		assertEquals("", c.apiKey);
		c.scrobbleMenuMusic = false;
		c.save(file);
		assertFalse(NeedleConfig.load(file).scrobbleMenuMusic);
		assertEquals(new Threshold(1.0, 0), c.threshold());
	}

	@Test
	void volumeChecks() {
		NeedleConfig c = new NeedleConfig();
		assertTrue(c.volumeAllows(0.5, 0.01, false));
		assertFalse(c.volumeAllows(0, 1, false), "master muted");
		assertFalse(c.volumeAllows(1, 0, false), "music muted");
		assertFalse(c.volumeAllows(1, 0, true), "jukebox muted");
		c.requireJukeboxVolume = false;
		assertTrue(c.volumeAllows(1, 0, true));
		assertFalse(c.volumeAllows(1, 0, false), "music check still on");
		c.requireMasterVolume = false;
		assertTrue(c.volumeAllows(0, 0, true));
	}

	@Test
	void apiKeyOverrideNeedsBothParts() {
		NeedleConfig c = new NeedleConfig();
		c.apiKey = "mine";
		assertEquals(BuiltInKeys.API_KEY, c.effectiveApiKey(), "secret missing: built-in key stays");
		c.apiSecret = "s";
		assertEquals("mine", c.effectiveApiKey());
		assertTrue(c.hasApiKey());
	}

	@Test
	void sessionSavedApartAndDeletedOnLogout(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("needle").resolve("session.json");
		assertNull(NeedleConfig.loadSession(file));
		NeedleConfig.saveSession(file, new LastFm.Session("skaffa", "k1"));
		assertEquals(new LastFm.Session("skaffa", "k1"), NeedleConfig.loadSession(file));
		NeedleConfig.saveSession(file, null);
		assertFalse(Files.exists(file));
	}
}
