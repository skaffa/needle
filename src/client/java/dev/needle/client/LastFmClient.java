package dev.needle.client;

import dev.needle.core.LastFm;
import dev.needle.core.NeedleConfig;
import dev.needle.core.ScrobbleQueue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * All Last.fm traffic, on one background thread so the game never waits for the network. Every field
 * below is only touched on that thread, except the volatile ones read by {@code /needle status}.
 */
final class LastFmClient {
	private static final long POLL_SECONDS = 5;
	/** auth.getSession polling stops after this many tries (5 minutes). */
	private static final int POLL_TRIES = 60;

	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "needle-lastfm");
		t.setDaemon(true);
		return t;
	});
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final NeedleConfig.Locations files;
	private final Supplier<NeedleConfig> config;

	private ScrobbleQueue queue;
	private final ScrobbleQueue.Backoff backoff = new ScrobbleQueue.Backoff();
	private ScheduledFuture<?> retry;
	private ScheduledFuture<?> poll;

	volatile LastFm.Session session;
	/** Why sending is stopped (bad session or key), or null. */
	volatile String blocked;
	volatile int queueSize;
	/** When the next retry runs (epoch ms), or 0. */
	volatile long retryAtMs;

	LastFmClient(NeedleConfig.Locations files, Supplier<NeedleConfig> config) {
		this.files = files;
		this.config = config;
	}

	void start() {
		exec.execute(() -> {
			queue = ScrobbleQueue.load(files.queue());
			queueSize = queue.size();
			session = NeedleConfig.loadSession(files.session());
			send();
		});
	}

	void enqueue(LastFm.Scrobble s) {
		exec.execute(() -> {
			try {
				queue.add(s);
			} catch (IOException e) {
				NeedleClient.LOG.error("needle: could not save the scrobble queue", e);
			}
			queueSize = queue.size();
			send();
		});
	}

	void nowPlaying(String artist, String track, String album, int durationS) {
		exec.execute(() -> {
			NeedleConfig cfg = config.get();
			LastFm.Session s = session;
			if (s == null || blocked != null || !cfg.hasApiKey()) {
				return;
			}
			try {
				String body = post(LastFm.updateNowPlaying(cfg.effectiveApiKey(), cfg.effectiveApiSecret(), s.key(), artist, track, album, durationS));
				LastFm.ApiError error = LastFm.error(body);
				if (error != null) {
					handleBlocking(error);
				}
			} catch (IOException | InterruptedException e) {
				NeedleClient.LOG.debug("needle: now playing not sent", e);
			}
		});
	}

	/** {@code /needle flush}: send now, ignoring the retry wait (but not a bad session or key). */
	void flush() {
		exec.execute(() -> {
			cancelRetry();
			backoff.succeed();
			send();
		});
	}

	void login() {
		exec.execute(() -> {
			NeedleConfig cfg = config.get();
			if (!cfg.hasApiKey()) {
				NeedleClient.say("No Last.fm API key. Set apiKey and apiSecret in config/needle/config.json, then /needle reload.");
				return;
			}
			if (poll != null) {
				poll.cancel(false);
			}
			try {
				String body = post(LastFm.getToken(cfg.effectiveApiKey(), cfg.effectiveApiSecret()));
				String token = LastFm.token(body);
				if (token == null) {
					LastFm.ApiError error = LastFm.error(body);
					NeedleClient.say("Login failed: " + (error != null ? error.message() + " (" + error.code() + ")" : "unexpected reply"));
					return;
				}
				NeedleClient.sayLink("Open this link and allow access, then come back (waiting up to 5 minutes):",
						LastFm.authUrl(cfg.effectiveApiKey(), token));
				int[] tries = {0};
				poll = exec.scheduleWithFixedDelay(() -> pollSession(token, tries), POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
			} catch (IOException | InterruptedException e) {
				NeedleClient.say("Login failed: could not reach Last.fm (" + e.getMessage() + ")");
			}
		});
	}

	private void pollSession(String token, int[] tries) {
		NeedleConfig cfg = config.get();
		if (++tries[0] > POLL_TRIES) {
			NeedleClient.say("Login timed out. Run /needle login again.");
			poll.cancel(false);
			return;
		}
		try {
			String body = post(LastFm.getSession(cfg.effectiveApiKey(), cfg.effectiveApiSecret(), token));
			LastFm.Session s = LastFm.session(body);
			if (s != null) {
				poll.cancel(false);
				session = s;
				blocked = null;
				NeedleConfig.saveSession(files.session(), s);
				NeedleClient.say("Logged in to Last.fm as " + s.name() + ".");
				send();
				return;
			}
			LastFm.ApiError error = LastFm.error(body);
			if (error != null && LastFm.actionFor(error.code()) != LastFm.ErrorAction.RETRY) {
				poll.cancel(false);
				NeedleClient.say("Login failed: " + error.message() + " (" + error.code() + ")");
			}
		} catch (IOException e) {
			NeedleClient.LOG.debug("needle: session poll failed", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	void logout() {
		exec.execute(() -> {
			if (poll != null) {
				poll.cancel(false);
			}
			session = null;
			blocked = null;
			try {
				NeedleConfig.saveSession(files.session(), null);
			} catch (IOException e) {
				NeedleClient.LOG.error("needle: could not delete the session file", e);
			}
			NeedleClient.say("Logged out. Scrobbles are kept in the queue until you log in again.");
		});
	}

	/** Config reloaded: a fixed API key may unblock sending. */
	void configChanged() {
		exec.execute(() -> {
			if (blocked != null && blocked.startsWith("API key")) {
				blocked = null;
				send();
			}
		});
	}

	private void send() {
		NeedleConfig cfg = config.get();
		LastFm.Session s = session;
		if (s == null || blocked != null || !cfg.hasApiKey() || queue == null || queue.isEmpty()
				|| (retry != null && !retry.isDone())) {
			return;
		}
		List<LastFm.Scrobble> batch = queue.nextBatch();
		try {
			String body = post(LastFm.scrobble(cfg.effectiveApiKey(), cfg.effectiveApiSecret(), s.key(), batch));
			LastFm.ApiError error = LastFm.error(body);
			if (error != null) {
				switch (LastFm.actionFor(error.code())) {
					case RETRY -> scheduleRetry("Last.fm error " + error.code() + ": " + error.message());
					case DROP -> {
						NeedleClient.LOG.warn("needle: Last.fm rejected {} scrobbles ({}: {}); dropped", batch.size(), error.code(), error.message());
						remove(batch);
					}
					default -> handleBlocking(error);
				}
				return;
			}
			List<LastFm.ScrobbleResult> results = LastFm.scrobbleResults(body);
			if (results.size() != batch.size()) {
				scheduleRetry("unexpected reply from Last.fm");
				return;
			}
			for (int i = 0; i < batch.size(); i++) {
				if (!results.get(i).accepted()) {
					NeedleClient.LOG.warn("needle: Last.fm ignored {} - {} ({})", batch.get(i).artist(), batch.get(i).track(),
							results.get(i).ignoredMessage());
				}
			}
			remove(batch);
			backoff.succeed();
			retryAtMs = 0;
			if (!queue.isEmpty()) {
				exec.execute(this::send);
			}
		} catch (IOException e) {
			scheduleRetry(e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void remove(List<LastFm.Scrobble> batch) {
		try {
			queue.remove(batch);
		} catch (IOException e) {
			NeedleClient.LOG.error("needle: could not save the scrobble queue", e);
		}
		queueSize = queue.size();
	}

	private void handleBlocking(LastFm.ApiError error) {
		switch (LastFm.actionFor(error.code())) {
			case RELOGIN -> {
				blocked = "Last.fm session is no longer valid";
				session = null;
				try {
					NeedleConfig.saveSession(files.session(), null);
				} catch (IOException e) {
					NeedleClient.LOG.error("needle: could not delete the session file", e);
				}
				NeedleClient.say("Last.fm login expired (" + error.message() + "). Run /needle login.");
			}
			case BAD_KEY -> {
				blocked = "API key rejected: " + error.message();
				NeedleClient.say("Last.fm rejected the API key (" + error.message() + "). Check config/needle/config.json.");
			}
			default -> NeedleClient.LOG.debug("needle: Last.fm error {}: {}", error.code(), error.message());
		}
	}

	private void scheduleRetry(String why) {
		long delay = backoff.fail();
		retryAtMs = System.currentTimeMillis() + delay;
		NeedleClient.LOG.info("needle: scrobbling failed ({}); retrying in {} s", why, delay / 1000);
		retry = exec.schedule(() -> {
			retry = null;
			send();
		}, delay, TimeUnit.MILLISECONDS);
	}

	private void cancelRetry() {
		if (retry != null) {
			retry.cancel(false);
			retry = null;
		}
		retryAtMs = 0;
	}

	private String post(LastFm.Request request) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(LastFm.API_URL))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("User-Agent", "Needle Minecraft mod")
				.POST(HttpRequest.BodyPublishers.ofString(LastFm.formBody(request)))
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() >= 500) {
			throw new IOException("HTTP " + res.statusCode());
		}
		return res.body();
	}
}
