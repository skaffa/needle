package dev.needle.client;

import dev.needle.core.ListenTracker;
import dev.needle.core.NeedleConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Development aid, off by default. {@code ./gradlew runClient -Pdevtest=<scenario>} runs one scripted
 * scenario, logs {@code CHECK PASS} / {@code CHECK FAIL} lines and quits:
 * <ul>
 * <li>{@code 1} (default): two jukeboxes, focus switching and resume (singleplayer)
 * <li>{@code rules}: pause menu, muted sliders, Now Playing, dimension change (singleplayer)
 * <li>{@code pack}: a resource pack replacing a disc is not scrobbled (singleplayer)
 * <li>{@code offline}: scrobble while Last.fm is unreachable, queue, retry, flush (singleplayer)
 * <li>{@code screen}: settings screen buttons and text fields (title screen)
 * <li>{@code gallery}: daytime screenshot of a playing jukebox with the HUD line, for the Modrinth gallery
 * <li>{@code server}: missed stop event and late join on a dedicated server (multiplayer, op)
 * </ul>
 * Singleplayer commands run on the integrated server, so test worlds need no cheats.
 */
final class DevTest {
	private static final String MODE = System.getProperty("needle.devtest", "");
	static final boolean ON = !MODE.isEmpty();
	private static int ticks = -1;
	private static BlockPos origin;
	private static long mark;
	private static int passed;
	private static int failed;

	private DevTest() {
	}

	static void tick(Minecraft mc) {
		if (!ON) {
			return;
		}
		if (MODE.equals("screen")) {
			screen(mc);
			return;
		}
		boolean multiplayer = MODE.equals("server");
		if (mc.level == null || mc.player == null || (!multiplayer && mc.getSingleplayerServer() == null)) {
			return;
		}
		ticks++;
		if (ticks == 0) {
			origin = mc.player.blockPosition();
		}
		switch (MODE) {
			case "rules" -> rules(mc);
			case "pack" -> pack(mc);
			case "offline" -> offline(mc);
			case "server" -> server(mc);
			case "gallery" -> gallery(mc);
			default -> jukeboxes(mc);
		}
		if (ticks % 100 == 0) {
			StringBuilder sb = new StringBuilder("devtest t=" + ticks + ":");
			for (ListenTracker.ListenView v : NeedleClient.tracker.listens()) {
				sb.append("\n  ").append(v.focused() ? "> " : "  ").append(NeedleClient.describe(v));
			}
			sb.append("\n  queue=").append(NeedleClient.lastFm.queueSize);
			NeedleClient.LOG.info(sb.toString());
		}
	}

	// ---- 1: two jukeboxes ----

	private static void jukeboxes(Minecraft mc) {
		BlockPos a = origin.offset(3, 0, 0);
		BlockPos b = origin.offset(40, 0, 0);
		switch (ticks) {
			case 100 -> jukebox(mc, a, "cat");
			case 400 -> shot(mc, "1-cat");
			case 500 -> {
				jukebox(mc, b, "far");
				run(mc, "tp @p " + (b.getX() - 2) + " " + b.getY() + " " + b.getZ());
			}
			case 800 -> shot(mc, "2-far");
			// walk back to cat: its earlier progress must resume, far keeps its own
			case 900 -> run(mc, "tp @p " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
			// stop far by breaking its jukebox (stop event)
			case 1000 -> run(mc, "setblock " + xyz(b) + " minecraft:air");
			case 1100 -> shot(mc, "3-end");
			case 1120 -> finish(mc);
			default -> {
			}
		}
	}

	// ---- gallery: a daytime screenshot of a jukebox playing with the HUD line ----

	private static void gallery(Minecraft mc) {
		// an untouched spot of the flat test world, away from earlier test builds
		BlockPos stand = new BlockPos(200, -60, 200);
		BlockPos box = stand.offset(0, 0, 4);
		switch (ticks) {
			case 1 -> {
				// Last.fm's own rule for the shot, not the dev config's low test value (not saved)
				NeedleClient.config.fraction = 0.5;
				NeedleClient.config.maxRequiredMinutes = 4;
				NeedleClient.config.showHud = true;
				NeedleClient.tracker.setThreshold(NeedleClient.config.threshold());
				mc.options.guiScale().set(4);
			}
			case 20 -> {
				run(mc, "time set 6000");
				run(mc, "weather clear");
				run(mc, "gamerule spawn_mobs false");
				run(mc, "tp @p " + (stand.getX() + 0.5) + " " + stand.getY() + " " + (stand.getZ() + 0.5) + " 0 22");
				run(mc, "item replace entity @p weapon.mainhand with minecraft:music_disc_cat");
			}
			case 100 -> {
				run(mc, "kill @e[type=!minecraft:player]");
				jukebox(mc, box, "cat");
				run(mc, "setblock " + xyz(box.offset(-1, 0, 0)) + " minecraft:poppy");
				run(mc, "setblock " + xyz(box.offset(1, 0, 1)) + " minecraft:dandelion");
				run(mc, "setblock " + xyz(box.offset(-2, 0, 2)) + " minecraft:oxeye_daisy");
				run(mc, "setblock " + xyz(box.offset(2, 0, -1)) + " minecraft:cornflower");
			}
			case 500 -> shot(mc, "gallery");
			case 540 -> {
				run(mc, "fill " + xyz(box.offset(-2, 0, -1)) + " " + xyz(box.offset(2, 0, 2)) + " minecraft:air");
				mc.options.guiScale().set(0);
				finish(mc);
			}
			default -> {
			}
		}
	}

	// ---- rules: pause, mute, Now Playing, dimension change ----

	private static void rules(Minecraft mc) {
		switch (ticks) {
			case 1 -> requireWholeTrack();
			case 100 -> jukebox(mc, origin.offset(3, 0, 0), "13");
			case 300 -> {
				check("now playing sent", "C418 - 13".equals(NeedleClient.lastFm.lastNowPlaying),
						"last accepted: " + NeedleClient.lastFm.lastNowPlaying);
				mc.pauseGame(false);
			}
			// baseline once the pause took effect (the first paused tick also saves the world)
			case 320 -> mark = listened("records/13");
			case 400 -> {
				check("pause menu stops jukebox time", mc.isPaused() && listened("records/13") - mark < 300,
						"paused " + mc.isPaused() + ", gained " + (listened("records/13") - mark) + " ms");
				mc.gui.setScreen(null);
				mark = listened("records/13");
			}
			case 500 -> {
				check("time counts again after unpausing", listened("records/13") - mark > 3000, (listened("records/13") - mark) + " ms");
				volume(mc, SoundSource.RECORDS, 0);
				mark = listened("records/13");
			}
			case 600 -> {
				check("Jukebox/Note Blocks at 0% stops time", listened("records/13") >= 0 && listened("records/13") - mark < 300,
						"gained " + (listened("records/13") - mark) + " ms");
				volume(mc, SoundSource.RECORDS, 1);
				mark = listened("records/13");
			}
			case 700 -> {
				check("song survives muting and counts again", listened("records/13") - mark > 3000, (listened("records/13") - mark) + " ms");
				volume(mc, SoundSource.MASTER, 0);
				mark = listened("records/13");
			}
			case 800 -> {
				check("Master at 0% stops time", listened("records/13") >= 0 && listened("records/13") - mark < 300,
						"gained " + (listened("records/13") - mark) + " ms");
				volume(mc, SoundSource.MASTER, 1);
				mark = listened("records/13");
			}
			case 900 -> {
				check("counts again after unmuting Master", listened("records/13") - mark > 3000, (listened("records/13") - mark) + " ms");
				run(mc, "execute in minecraft:the_nether run tp @p " + origin.getX() + " 128 " + origin.getZ());
			}
			case 1000 -> {
				check("dimension change ends the jukebox listen", listened("records/13") < 0, "listened " + listened("records/13"));
				run(mc, "execute in minecraft:overworld run tp @p " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
			}
			case 1100 -> {
				check("coming back does not pick the song up again", listened("records/13") < 0,
						"vanilla does not resend the start event either");
				check("nothing scrobbled (whole track required)", NeedleClient.lastFm.queueSize == 0, "queue " + NeedleClient.lastFm.queueSize);
				finish(mc);
			}
			default -> {
			}
		}
	}

	// ---- pack: resource pack replacing records/cat.ogg ----

	private static final String PACK = "needle-devtest";
	private static boolean packReady;

	private static void pack(Minecraft mc) {
		switch (ticks) {
			case 20 -> {
				try {
					writePack(mc);
					mc.getResourcePackRepository().reload();
					List<String> selected = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
					selected.add("file/" + PACK);
					mc.getResourcePackRepository().setSelected(selected);
					mc.reloadResourcePacks().thenRun(() -> packReady = true);
				} catch (Exception e) {
					check("test pack installed", false, e.toString());
				}
			}
			case 300 -> {
				check("test pack active", packReady && mc.getResourcePackRepository().getSelectedIds().contains("file/" + PACK),
						"selected " + mc.getResourcePackRepository().getSelectedIds());
				jukebox(mc, origin.offset(3, 0, 0), "cat");
				jukebox(mc, origin.offset(-3, 0, 0), "far");
			}
			case 500 -> {
				check("replaced disc is not tracked", listened("records/cat") < 0, "listened " + listened("records/cat"));
				check("untouched disc still is", listened("records/far") > 3000, "listened " + listened("records/far"));
				run(mc, "setblock " + xyz(origin.offset(3, 0, 0)) + " minecraft:air");
				run(mc, "setblock " + xyz(origin.offset(-3, 0, 0)) + " minecraft:air");
				List<String> selected = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
				selected.remove("file/" + PACK);
				mc.getResourcePackRepository().setSelected(selected);
				mc.reloadResourcePacks();
			}
			case 700 -> finish(mc);
			default -> {
			}
		}
	}

	/** run/resourcepacks/needle-devtest: records/cat.ogg replaced by a copy of records/far.ogg. */
	private static void writePack(Minecraft mc) throws Exception {
		Path dir = mc.gameDirectory.toPath().resolve("resourcepacks").resolve(PACK);
		Path ogg = dir.resolve("assets/minecraft/sounds/records/cat.ogg");
		Files.createDirectories(ogg.getParent());
		Files.writeString(dir.resolve("pack.mcmeta"),
				"{\"pack\":{\"description\":\"Needle dev test: replaces cat\",\"min_format\":97,\"max_format\":97}}");
		Optional<Resource> far = mc.getResourceManager().getResource(Identifier.withDefaultNamespace("sounds/records/far.ogg"));
		try (InputStream in = far.orElseThrow().open()) {
			Files.write(ogg, in.readAllBytes());
		}
	}

	// ---- offline: Last.fm unreachable, then back ----

	private static void offline(Minecraft mc) {
		switch (ticks) {
			case 1 -> LastFmClient.apiUrl = "http://127.0.0.1:9/"; // nothing listens on the discard port
			case 100 -> jukebox(mc, origin.offset(3, 0, 0), "11");
			case 500 -> check("scrobble kept in the queue while offline",
					NeedleClient.lastFm.queueSize == 1 && NeedleClient.lastFm.retryAtMs > 0,
					"queue " + NeedleClient.lastFm.queueSize + ", retry in "
							+ (NeedleClient.lastFm.retryAtMs - System.currentTimeMillis()) / 1000 + " s");
			case 520 -> {
				LastFmClient.apiUrl = dev.needle.core.LastFm.API_URL;
				NeedleClient.lastFm.flush();
			}
			case 620 -> {
				check("queue sent after coming back online", NeedleClient.lastFm.queueSize == 0 && NeedleClient.lastFm.retryAtMs == 0,
						"queue " + NeedleClient.lastFm.queueSize);
				run(mc, "setblock " + xyz(origin.offset(3, 0, 0)) + " minecraft:air");
				finish(mc);
			}
			default -> {
			}
		}
	}

	// ---- server: missed stop event, late join ----

	private static void server(Minecraft mc) {
		BlockPos a = origin.offset(3, 0, 0);
		BlockPos away = origin.offset(500, 0, 0);
		BlockPos b = origin.offset(600, 0, 0);
		switch (ticks) {
			case 40 -> {
				run(mc, "forceload add " + a.getX() + " " + a.getZ());
				run(mc, "forceload add " + b.getX() + " " + b.getZ());
			}
			case 100 -> {
				jukebox(mc, a, "11");
				mark = System.currentTimeMillis();
			}
			case 300 -> check("jukebox heard on a server", listened("records/11") > 3000, "listened " + listened("records/11"));
			case 320 -> tp(mc, away);
			// break A while far away (the client already got a stop event when it left)
			case 420 -> run(mc, "setblock " + xyz(a) + " minecraft:air");
			// late join: B starts while the player is 100 blocks away, then the player walks up to it
			case 440 -> jukebox(mc, b, "13");
			case 540 -> tp(mc, b.offset(-2, 0, 0));
			case 740 -> check("late join: a song that started out of range is not tracked", listened("records/13") < 0,
					"listened " + listened("records/13"));
			default -> {
			}
		}
		if (ticks > 740 && ticks % 20 == 0 && mark > 0) {
			long age = System.currentTimeMillis() - mark;
			if (listened("records/11") < 0) {
				// 26.3 sends a stop event when the player leaves, so the listen usually ends then; the song
				// length is only the fallback for a stop that really is lost
				check("listen ends after walking away", true, "ended within " + age / 1000 + " s (song is 71 s)");
				mark = 0;
				cleanupServer(mc, a, b);
				finish(mc);
			} else if (age > 120_000) {
				check("listen ends after walking away", false, "still listed after " + age / 1000 + " s");
				mark = 0;
				cleanupServer(mc, a, b);
				finish(mc);
			}
		}
	}

	private static void cleanupServer(Minecraft mc, BlockPos a, BlockPos b) {
		run(mc, "setblock " + xyz(b) + " minecraft:air");
		run(mc, "forceload remove " + a.getX() + " " + a.getZ());
		run(mc, "forceload remove " + b.getX() + " " + b.getZ());
	}

	// ---- screen: settings screen controls ----

	private static void screen(Minecraft mc) {
		ticks++;
		NeedleConfig c = NeedleClient.config;
		switch (ticks) {
			case 200 -> {
				mark = Math.round(c.fraction * 100);
				mc.gui.setScreen(new NeedleScreen(mc.gui.screen()));
			}
			case 220 -> {
				List<Button> minus = buttons(mc, "-");
				minus.get(0).onPress(null);
				NeedleConfig saved = NeedleConfig.load(NeedleClient.FILES.config());
				check("'-' lowers Required part and saves", Math.round(c.fraction * 100) == mark - 5 && Math.round(saved.fraction * 100) == mark - 5,
						"now " + Math.round(c.fraction * 100) + "%, file " + Math.round(saved.fraction * 100) + "%");
				buttons(mc, "+").get(0).onPress(null);
				check("'+' raises it back", Math.round(c.fraction * 100) == mark, "now " + Math.round(c.fraction * 100) + "%");
			}
			case 230 -> {
				boolean before = c.chatOnScrobble;
				// left column On/Off buttons top to bottom: Scrobbling, Now Playing, HUD line, Chat on scrobble
				Button chat = onOffButtons(mc).get(3);
				chat.onPress(null);
				check("toggle flips Chat on scrobble", c.chatOnScrobble != before && NeedleConfig.load(NeedleClient.FILES.config()).chatOnScrobble != before,
						"label now " + chat.getMessage().getString());
				chat.onPress(null);
				check("and back", c.chatOnScrobble == before, "label now " + chat.getMessage().getString());
			}
			case 240 -> {
				EditBox key = editBoxes(mc).get(0);
				key.setValue("devtest-key");
				check("API key field saves", "devtest-key".equals(NeedleConfig.load(NeedleClient.FILES.config()).apiKey), "config.apiKey " + c.apiKey);
				check("half an override keeps the built-in key", c.effectiveApiKey().equals(dev.needle.core.BuiltInKeys.API_KEY),
						"secret empty, so the built-in key stays in use");
				key.setValue("");
				check("cleared again", NeedleConfig.load(NeedleClient.FILES.config()).apiKey.isEmpty(), "");
			}
			case 250 -> shot(mc, "screen");
			case 270 -> {
				mc.gui.screen().onClose();
				finish(mc);
			}
			default -> {
			}
		}
	}

	private static List<Button> buttons(Minecraft mc, String label) {
		List<Button> out = new ArrayList<>();
		for (GuiEventListener l : mc.gui.screen().children()) {
			if (l instanceof Button b && b.getMessage().getString().equals(label)) {
				out.add(b);
			}
		}
		return out;
	}

	private static List<Button> onOffButtons(Minecraft mc) {
		List<Button> out = buttons(mc, "On");
		out.addAll(buttons(mc, "Off"));
		out.sort((x, y) -> x.getX() != y.getX() ? Integer.compare(x.getX(), y.getX()) : Integer.compare(x.getY(), y.getY()));
		return out;
	}

	private static List<EditBox> editBoxes(Minecraft mc) {
		List<EditBox> out = new ArrayList<>();
		for (GuiEventListener l : mc.gui.screen().children()) {
			if (l instanceof EditBox e) {
				out.add(e);
			}
		}
		return out;
	}

	// ---- helpers ----

	/** Whole-track threshold for this run only (not saved), so checks never trigger a scrobble. */
	private static void requireWholeTrack() {
		NeedleClient.config.fraction = 1.0;
		NeedleClient.config.maxRequiredMinutes = 0;
		NeedleClient.tracker.setThreshold(NeedleClient.config.threshold());
	}

	/** Listened ms of the listen with this track id, or -1 when there is none. */
	private static long listened(String trackId) {
		for (ListenTracker.ListenView v : NeedleClient.tracker.listens()) {
			if (v.track().id().equals(trackId)) {
				return v.listenedMs();
			}
		}
		return -1;
	}

	private static void volume(Minecraft mc, SoundSource source, double value) {
		mc.options.getSoundSourceOptionInstance(source).set(value);
		NeedleClient.LOG.info("devtest t={}: {} volume {}", ticks, source.getName(), value);
	}

	private static void jukebox(Minecraft mc, BlockPos pos, String disc) {
		run(mc, "setblock " + xyz(pos) + " minecraft:jukebox");
		run(mc, "item replace block " + xyz(pos) + " container.0 with minecraft:music_disc_" + disc);
	}

	private static void tp(Minecraft mc, BlockPos pos) {
		run(mc, "tp @s " + xyz(pos));
	}

	private static void check(String name, boolean ok, String detail) {
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		NeedleClient.LOG.info("CHECK {} {}: {}", ok ? "PASS" : "FAIL", name, detail);
	}

	private static void finish(Minecraft mc) {
		NeedleClient.LOG.info("CHECK DONE {}: {} passed, {} failed", MODE, passed, failed);
		// undo the run-only threshold
		NeedleClient.config = NeedleConfig.load(NeedleClient.FILES.config());
		mc.stop();
	}

	private static String xyz(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	/** Singleplayer: on the integrated server (no cheats needed). Multiplayer: as the player (needs op). */
	private static void run(Minecraft mc, String command) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack().withEntity(server.getPlayerList().getPlayer(mc.player.getUUID())), command));
		} else {
			mc.player.connection.sendCommand(command);
		}
		NeedleClient.LOG.info("devtest t={}: /{}", ticks, command);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "needle-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> NeedleClient.LOG.info("devtest screenshot {}: {}", name, msg.getString()));
	}
}
