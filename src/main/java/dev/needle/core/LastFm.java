package dev.needle.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Last.fm API 2.0 requests and responses, without any I/O: the client posts {@link #formBody} to
 * {@link #API_URL} and hands the response text back here. See https://www.last.fm/api.
 */
public final class LastFm {
	public static final String API_URL = "https://ws.audioscrobbler.com/2.0/";
	public static final int MAX_BATCH = 50;

	private LastFm() {
	}

	/** A signed request: API parameters plus {@code api_sig} and {@code format=json}. */
	public record Request(Map<String, String> params) {
		public String method() {
			return params.get("method");
		}
	}

	/** One track in a scrobble batch. */
	public record Scrobble(String artist, String track, long timestampEpochS, int durationS, boolean chosenByUser) {
	}

	public record Session(String name, String key) {
	}

	// ---- signing ----

	/**
	 * {@code api_sig}: md5 of every parameter except {@code format} and {@code callback}, sorted by name,
	 * written as name followed by value, then the secret, as lowercase hex.
	 */
	public static String signature(Map<String, String> params, String secret) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
			if (e.getKey().equals("format") || e.getKey().equals("callback")) {
				continue;
			}
			sb.append(e.getKey()).append(e.getValue());
		}
		sb.append(secret);
		try {
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			return HexFormat.of().formatHex(md5.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 missing from the JVM", e);
		}
	}

	private static Request signed(Map<String, String> params, String apiKey, String secret) {
		TreeMap<String, String> p = new TreeMap<>(params);
		p.put("api_key", apiKey);
		p.put("api_sig", signature(p, secret));
		p.put("format", "json");
		return new Request(p);
	}

	/** {@code application/x-www-form-urlencoded} body for a POST. */
	public static String formBody(Request request) {
		StringJoiner j = new StringJoiner("&");
		for (Map.Entry<String, String> e : request.params().entrySet()) {
			j.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
					+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return j.toString();
	}

	// ---- requests ----

	public static Request getToken(String apiKey, String secret) {
		return signed(Map.of("method", "auth.getToken"), apiKey, secret);
	}

	/** Page where the player approves the token in their browser. */
	public static String authUrl(String apiKey, String token) {
		return "https://www.last.fm/api/auth/?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
				+ "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
	}

	public static Request getSession(String apiKey, String secret, String token) {
		return signed(Map.of("method", "auth.getSession", "token", token), apiKey, secret);
	}

	public static Request updateNowPlaying(String apiKey, String secret, String sessionKey, String artist,
			String track, int durationS) {
		TreeMap<String, String> p = new TreeMap<>();
		p.put("method", "track.updateNowPlaying");
		p.put("sk", sessionKey);
		p.put("artist", artist);
		p.put("track", track);
		if (durationS > 0) {
			p.put("duration", Integer.toString(durationS));
		}
		return signed(p, apiKey, secret);
	}

	public static Request scrobble(String apiKey, String secret, String sessionKey, List<Scrobble> batch) {
		if (batch.isEmpty() || batch.size() > MAX_BATCH) {
			throw new IllegalArgumentException("batch size must be 1-" + MAX_BATCH + ": " + batch.size());
		}
		TreeMap<String, String> p = new TreeMap<>();
		p.put("method", "track.scrobble");
		p.put("sk", sessionKey);
		for (int i = 0; i < batch.size(); i++) {
			Scrobble s = batch.get(i);
			p.put("artist[" + i + "]", s.artist());
			p.put("track[" + i + "]", s.track());
			p.put("timestamp[" + i + "]", Long.toString(s.timestampEpochS()));
			if (s.durationS() > 0) {
				p.put("duration[" + i + "]", Integer.toString(s.durationS()));
			}
			p.put("chosenByUser[" + i + "]", s.chosenByUser() ? "1" : "0");
		}
		return signed(p, apiKey, secret);
	}

	// ---- responses ----

	/** What to do after a Last.fm error code. */
	public enum ErrorAction {
		/** Temporary (service offline, rate limit, operation failed): keep and try later. */
		RETRY,
		/** Session key revoked or invalid: stop sending until the player logs in again. */
		RELOGIN,
		/** API key invalid or suspended: stop sending until the key is fixed. */
		BAD_KEY,
		/** The request itself is wrong: sending it again will not help. */
		DROP
	}

	public static ErrorAction actionFor(int code) {
		return switch (code) {
			// 14 = token not authorized yet (auth.getSession while the player has not approved): keep polling
			case 8, 11, 14, 16, 29 -> ErrorAction.RETRY;
			// 15 = token expired (login took over an hour)
			case 9, 15 -> ErrorAction.RELOGIN;
			case 4, 10, 26 -> ErrorAction.BAD_KEY;
			default -> ErrorAction.DROP;
		};
	}

	/** A Last.fm error response, or null when the body is not one. */
	public record ApiError(int code, String message) {
	}

	public static ApiError error(String body) {
		JsonObject o = object(body);
		if (o == null || !o.has("error")) {
			return null;
		}
		return new ApiError(o.get("error").getAsInt(), o.has("message") ? o.get("message").getAsString() : "");
	}

	/** Token from {@code auth.getToken}, or null. */
	public static String token(String body) {
		JsonObject o = object(body);
		return o != null && o.has("token") ? o.get("token").getAsString() : null;
	}

	/** Session from {@code auth.getSession}, or null. */
	public static Session session(String body) {
		JsonObject o = object(body);
		if (o == null || !o.has("session")) {
			return null;
		}
		JsonObject s = o.getAsJsonObject("session");
		return new Session(s.get("name").getAsString(), s.get("key").getAsString());
	}

	/** Per-track outcome of a scrobble batch, in request order. */
	public record ScrobbleResult(boolean accepted, String ignoredCode, String ignoredMessage) {
	}

	/**
	 * Results of {@code track.scrobble}, one per submitted track. Last.fm returns a single object
	 * instead of an array when one track was sent.
	 */
	public static List<ScrobbleResult> scrobbleResults(String body) {
		JsonObject o = object(body);
		List<ScrobbleResult> out = new ArrayList<>();
		if (o == null || !o.has("scrobbles")) {
			return out;
		}
		JsonElement list = o.getAsJsonObject("scrobbles").get("scrobble");
		JsonArray items = new JsonArray();
		if (list != null && list.isJsonArray()) {
			items = list.getAsJsonArray();
		} else if (list != null && list.isJsonObject()) {
			items.add(list);
		}
		for (JsonElement e : items) {
			JsonObject im = e.getAsJsonObject().getAsJsonObject("ignoredMessage");
			String code = im != null && im.has("code") ? im.get("code").getAsString() : "0";
			String text = im != null && im.has("#text") ? im.get("#text").getAsString() : "";
			out.add(new ScrobbleResult("0".equals(code), code, text));
		}
		return out;
	}

	private static JsonObject object(String body) {
		try {
			JsonElement e = JsonParser.parseString(body);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
