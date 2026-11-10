package dev.needle.client;

import dev.needle.core.LastFm;
import dev.needle.core.NeedleConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Every setting in config/needle/config.json on one screen (Mod Menu "Configure"). Changes apply and save immediately. */
public final class NeedleScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA8A8A8;
	private static final int ROW = 21;
	private static final int H = 20;
	private static final int TOP = 30;

	private final Screen parent;
	private final List<Label> labels = new ArrayList<>();
	private final List<Value> values = new ArrayList<>();
	private int colW;

	private record Label(String text, int x, int y, int color) {
	}

	/** A number shown between its - and + buttons, redrawn every frame. */
	private record Value(Supplier<String> text, int centerX, int y) {
	}

	public NeedleScreen(Screen parent) {
		super(Component.literal("Needle"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		clearWidgets();
		labels.clear();
		values.clear();
		NeedleConfig c = NeedleClient.config;
		colW = Math.min(200, (width - 36) / 2);
		int left = width / 2 - colW - 8;
		int right = width / 2 + 8;

		int y = TOP;
		y = toggle(left, y, "Scrobbling", () -> c.enabled, v -> c.enabled = v, "Master switch, same as /needle pause and /needle resume");
		y = number(left, y, "Required part", () -> Math.round(c.fraction * 100) + "%",
				() -> c.fraction = Math.max(0.1, round(c.fraction - 0.05)),
				() -> c.fraction = Math.min(1.0, round(c.fraction + 0.05)),
				"How much of a track you must hear before it is scrobbled. Last.fm's own rule is 50%.");
		y = number(left, y, "Max required", () -> c.maxRequiredMinutes == 0 ? "no cap" : c.maxRequiredMinutes + " min",
				() -> c.maxRequiredMinutes = Math.max(0, c.maxRequiredMinutes - 1),
				() -> c.maxRequiredMinutes = Math.min(30, c.maxRequiredMinutes + 1),
				"Long tracks scrobble after at most this much listening (Last.fm: 4 minutes). 0 = no cap.");
		y = number(left, y, "Jukebox range", () -> c.listenRadius + " blocks",
				() -> c.listenRadius = Math.max(1, c.listenRadius - 4),
				() -> c.listenRadius = Math.min(NeedleConfig.MAX_LISTEN_RADIUS, c.listenRadius + 4),
				"How close you must be to a jukebox for its song to count. Jukeboxes are silent past 64 blocks.");
		y = toggle(left, y, "Now Playing", () -> c.sendNowPlaying, v -> c.sendNowPlaying = v, "Show the track as Now Playing on your Last.fm profile");
		y = toggle(left, y, "HUD line", () -> c.showHud, v -> c.showHud = v, "Top-left line with the track being counted and its progress");
		toggle(left, y, "Chat on scrobble", () -> c.chatOnScrobble, v -> c.chatOnScrobble = v, "A chat message each time a track is scrobbled");

		y = TOP;
		y = toggle(right, y, "Game music", () -> c.scrobbleGameMusic, v -> c.scrobbleGameMusic = v,
				"Music while playing: overworld, nether, end, underwater, creative, credits");
		y = toggle(right, y, "Menu music", () -> c.scrobbleMenuMusic, v -> c.scrobbleMenuMusic = v, "Music on the title screen and other menus");
		y = toggle(right, y, "Jukeboxes", () -> c.scrobbleJukebox, v -> c.scrobbleJukebox = v, "Music discs playing in jukeboxes near you");
		y = toggle(right, y, "Need Master > 0%", () -> c.requireMasterVolume, v -> c.requireMasterVolume = v,
				"Nothing counts while the Master volume slider is at 0%");
		y = toggle(right, y, "Need Music > 0%", () -> c.requireMusicVolume, v -> c.requireMusicVolume = v,
				"Game and menu music count only while the Music slider is above 0%");
		y = toggle(right, y, "Need Jukebox > 0%", () -> c.requireJukeboxVolume, v -> c.requireJukeboxVolume = v,
				"Jukebox songs count only while the Jukebox/Note Blocks slider is above 0%");
		y = textField(right, y, "API key", c.apiKey, v -> c.apiKey = v,
				"Your own Last.fm API key. Leave empty to use the one built into the mod.");
		textField(right, y, "API secret", c.apiSecret, v -> c.apiSecret = v,
				"Shared secret for your own API key. Both must be set to replace the built-in key.");

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(width / 2 - 100, height - 28, 200, H).build());
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private int toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set, String tip) {
		labels.add(new Label(label, x, y + 6, GRAY));
		int bw = colW / 2;
		addRenderableWidget(Button.builder(onOff(get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			changed();
			b.setMessage(onOff(get.getAsBoolean()));
		}).bounds(x + colW - bw, y, bw, H).tooltip(Tooltip.create(Component.literal(tip))).build());
		return y + ROW;
	}

	private int number(int x, int y, String label, Supplier<String> text, Runnable dec, Runnable inc, String tip) {
		labels.add(new Label(label, x, y + 6, GRAY));
		int bw = colW / 2;
		int step = bw / 4;
		Tooltip tooltip = Tooltip.create(Component.literal(tip));
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			dec.run();
			changed();
		}).bounds(x + colW - bw, y, step, H).tooltip(tooltip).build());
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			inc.run();
			changed();
		}).bounds(x + colW - step, y, step, H).tooltip(tooltip).build());
		values.add(new Value(text, x + colW - bw / 2, y + 6));
		return y + ROW;
	}

	private int textField(int x, int y, String label, String value, Consumer<String> set, String tip) {
		labels.add(new Label(label, x, y + 6, GRAY));
		int bw = colW / 2;
		EditBox box = new EditBox(font, x + colW - bw, y, bw, H, Component.literal(label));
		box.setMaxLength(64);
		box.setValue(value);
		box.setResponder(v -> set.accept(v.strip()));
		box.setTooltip(Tooltip.create(Component.literal(tip)));
		addRenderableWidget(box);
		return y + ROW;
	}

	private static Component onOff(boolean v) {
		return Component.literal(v ? "On" : "Off");
	}

	private static void changed() {
		NeedleClient.applyConfig();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, title, width / 2, 10, WHITE);
		for (Label l : labels) {
			g.text(font, Component.literal(l.text()), l.x(), l.y(), l.color());
		}
		for (Value v : values) {
			g.centeredText(font, Component.literal(v.text().get()), v.centerX(), v.y(), WHITE);
		}
		g.centeredText(font, Component.literal(account()), width / 2, height - 44, GRAY);
	}

	private static String account() {
		LastFmClient lf = NeedleClient.lastFm;
		LastFm.Session s = lf.session;
		String who = s != null ? "Last.fm: " + s.name() : "Last.fm: not logged in (/needle login in a world)";
		String queue = lf.queueSize > 0 ? ", " + lf.queueSize + " queued" : "";
		return who + queue + (lf.blocked != null ? " - " + lf.blocked : "");
	}

	@Override
	public void onClose() {
		NeedleClient.applyConfig();
		minecraft.gui.setScreen(parent);
	}
}
