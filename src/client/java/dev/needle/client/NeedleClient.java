package dev.needle.client;

import com.mojang.brigadier.context.CommandContext;

import dev.needle.client.mixin.MusicManagerAccessor;
import dev.needle.core.LastFm;
import dev.needle.core.ListenTracker;
import dev.needle.core.NeedleConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Entry point: per-tick tracking, scrobble events, the {@code /needle} command. */
public final class NeedleClient implements ClientModInitializer {
	public static final String MOD_ID = "needle";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	static final NeedleConfig.Locations FILES = NeedleConfig.Locations.in(FabricLoader.getInstance().getConfigDir());
	static NeedleConfig config = new NeedleConfig();
	static ListenTracker tracker;
	static LastFmClient lastFm;

	/** Game music instance seen last tick, with when it started and whether it began in the menus. */
	private static SoundInstance music;
	private static long musicStartedAtS;
	private static boolean musicInMenu;

	@Override
	public void onInitializeClient() {
		config = NeedleConfig.load(FILES.config());
		saveConfig(); // writes defaults on first start
		tracker = new ListenTracker(config.threshold());
		lastFm = new LastFmClient(FILES, () -> config);
		lastFm.start();

		ClientTickEvents.END_CLIENT_TICK.register(NeedleClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> JukeboxSongs.clear());
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"), NeedleHud::extract);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("needle")
				.executes(NeedleClient::status)
				.then(literal("status").executes(NeedleClient::status))
				.then(literal("login").executes(c -> {
					lastFm.login();
					return 1;
				}))
				.then(literal("logout").executes(c -> {
					lastFm.logout();
					return 1;
				}))
				.then(literal("pause").executes(c -> setEnabled(c, false)))
				.then(literal("resume").executes(c -> setEnabled(c, true)))
				.then(literal("flush").executes(c -> {
					lastFm.flush();
					c.getSource().sendFeedback(prefix("sending queued scrobbles"));
					return 1;
				}))
				.then(literal("reload").executes(c -> {
					config = NeedleConfig.load(FILES.config());
					tracker.setThreshold(config.threshold());
					lastFm.configChanged();
					c.getSource().sendFeedback(prefix("config reloaded"));
					return 1;
				}))));
		LOG.info("Needle loaded (config in {})", FILES.dir());
	}

	private static void tick(Minecraft mc) {
		DevTest.tick(mc);
		if (!config.enabled) {
			tracker.reset();
			return;
		}
		List<ListenTracker.Source> sources = new ArrayList<>();
		double master = mc.options.getSoundSourceVolume(SoundSource.MASTER);

		SoundInstance current = ((MusicManagerAccessor) mc.getMusicManager()).needle$currentMusic();
		if (current != music) {
			music = current;
			musicStartedAtS = System.currentTimeMillis() / 1000;
			musicInMenu = mc.level == null;
		}
		if (music != null && (musicInMenu ? config.scrobbleMenuMusic : config.scrobbleGameMusic)) {
			ListenTracker.Track track = VanillaTracks.music(mc, music);
			if (track != null) {
				boolean audible = mc.getSoundManager().isActive(music)
						&& config.volumeAllows(master, mc.options.getSoundSourceVolume(SoundSource.MUSIC), false);
				sources.add(new ListenTracker.Source(track,
						musicInMenu ? ListenTracker.Kind.MENU_MUSIC : ListenTracker.Kind.GAME_MUSIC, audible, 0, musicStartedAtS));
			}
		}
		if (config.scrobbleJukebox && mc.level != null && mc.player != null) {
			JukeboxSongs.collect(mc, config, master, sources);
		}

		for (ListenTracker.Event event : tracker.tick(System.nanoTime(), sources)) {
			switch (event) {
				case ListenTracker.NowPlaying np -> {
					if (config.sendNowPlaying) {
						lastFm.nowPlaying(np.track().name().artist(), np.track().name().title(), np.track().album(), durationS(np.track()));
					}
				}
				case ListenTracker.Scrobble s -> {
					lastFm.enqueue(new LastFm.Scrobble(s.track().name().artist(), s.track().name().title(), s.timestampEpochS(),
							durationS(s.track()), s.kind() == ListenTracker.Kind.JUKEBOX, s.track().album()));
					LOG.info("needle: scrobbled {}", s.track().name());
					if (config.chatOnScrobble) {
						say("scrobbled " + s.track().name());
					}
				}
			}
		}
	}

	private static int durationS(ListenTracker.Track track) {
		return track.lengthMs() > 0 ? (int) (track.lengthMs() / 1000) : 0;
	}

	private static int setEnabled(CommandContext<FabricClientCommandSource> c, boolean on) {
		config.enabled = on;
		saveConfig();
		c.getSource().sendFeedback(prefix(on ? "scrobbling resumed" : "scrobbling paused"));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> c) {
		FabricClientCommandSource src = c.getSource();
		LastFm.Session s = lastFm.session;
		src.sendFeedback(prefix((s != null ? "logged in as " + s.name() : "not logged in (/needle login)")
				+ (config.enabled ? "" : ", paused") + ", " + lastFm.queueSize + " queued"));
		if (lastFm.blocked != null) {
			src.sendFeedback(Component.literal("  stopped: " + lastFm.blocked).withStyle(ChatFormatting.RED));
		}
		if (lastFm.retryAtMs > 0) {
			src.sendFeedback(Component.literal("  retrying in " + Math.max(0, (lastFm.retryAtMs - System.currentTimeMillis()) / 1000) + " s")
					.withStyle(ChatFormatting.GRAY));
		}
		for (ListenTracker.ListenView v : tracker.listens()) {
			src.sendFeedback(Component.literal("  " + (v.focused() ? "> " : "  ") + describe(v))
					.withStyle(v.focused() ? ChatFormatting.WHITE : ChatFormatting.GRAY));
		}
		return 1;
	}

	/** e.g. {@code C418 - Sweden  1:12 / 1:48 (game music)}. */
	static String describe(ListenTracker.ListenView v) {
		String progress = v.scrobbled() ? "scrobbled"
				: v.requiredMs() < 0 ? time(v.listenedMs()) + " (not scrobblable)"
				: time(v.listenedMs()) + " / " + time(v.requiredMs());
		String kind = switch (v.kind()) {
			case MENU_MUSIC -> "menu music";
			case GAME_MUSIC -> "game music";
			case JUKEBOX -> v.instances() > 1 ? v.instances() + " jukeboxes" : "jukebox";
		};
		return v.track().name() + "  " + progress + " (" + kind + (v.audible() ? "" : ", not heard") + ")";
	}

	static String time(long ms) {
		long s = ms / 1000;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	static void saveConfig() {
		try {
			config.save(FILES.config());
		} catch (IOException e) {
			LOG.error("needle: could not save config", e);
		}
	}

	private static MutableComponent prefix(String text) {
		return Component.literal("Needle: ").withStyle(ChatFormatting.DARK_AQUA).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	/** Chat message from any thread; logged when no world is loaded. */
	static void say(String text) {
		send(prefix(text));
	}

	static void sayLink(String text, String url) {
		send(prefix(text + " ").append(Component.literal(url).withStyle(st -> st
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withUnderlined(true)
				.withColor(ChatFormatting.AQUA))));
	}

	private static void send(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(message);
			} else {
				LOG.info("needle: {}", message.getString());
			}
		});
	}
}
