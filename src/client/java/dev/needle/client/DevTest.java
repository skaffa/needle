package dev.needle.client;

import dev.needle.core.ListenTracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * Development aid, off by default. With {@code -Dneedle.devtest=1} in a singleplayer world it places two
 * jukeboxes, walks the player between them, logs the tracker state every 5 s, takes screenshots and
 * quits. Commands run on the integrated server, so the world needs no cheats. See README.
 */
final class DevTest {
	static final boolean ON = !System.getProperty("needle.devtest", "").isEmpty();
	private static final boolean SCREEN = "screen".equals(System.getProperty("needle.devtest", ""));
	private static int ticks = -1;
	private static BlockPos origin;

	private DevTest() {
	}

	static void tick(Minecraft mc) {
		if (SCREEN) {
			screenTick(mc);
			return;
		}
		if (!ON || mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
			return;
		}
		ticks++;
		if (ticks == 0) {
			origin = mc.player.blockPosition();
		}
		BlockPos a = origin.offset(3, 0, 0);
		BlockPos b = origin.offset(40, 0, 0);
		switch (ticks) {
			case 100 -> {
				run(mc, "setblock " + xyz(a) + " minecraft:jukebox");
				run(mc, "item replace block " + xyz(a) + " container.0 with minecraft:music_disc_cat");
			}
			case 400 -> shot(mc, "1-cat");
			case 500 -> {
				run(mc, "setblock " + xyz(b) + " minecraft:jukebox");
				run(mc, "item replace block " + xyz(b) + " container.0 with minecraft:music_disc_far");
				run(mc, "tp @p " + (b.getX() - 2) + " " + b.getY() + " " + b.getZ());
			}
			case 800 -> shot(mc, "2-far");
			// walk back to cat: its earlier progress must resume, far keeps its own
			case 900 -> run(mc, "tp @p " + (origin.getX()) + " " + origin.getY() + " " + origin.getZ());
			// stop far by breaking its jukebox (stop event)
			case 1000 -> run(mc, "setblock " + xyz(b) + " minecraft:air");
			case 1100 -> shot(mc, "3-end");
			case 1120 -> mc.stop();
			default -> {
			}
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

	/** Settings screen over the title screen: open, screenshot, quit. */
	private static void screenTick(Minecraft mc) {
		ticks++;
		switch (ticks) {
			case 200 -> mc.gui.setScreen(new NeedleScreen(mc.gui.screen()));
			case 240 -> shot(mc, "screen");
			case 260 -> mc.stop();
			default -> {
			}
		}
	}

	private static String xyz(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	private static void run(Minecraft mc, String command) {
		MinecraftServer server = mc.getSingleplayerServer();
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		NeedleClient.LOG.info("devtest t={}: /{}", ticks, command);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "needle-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> NeedleClient.LOG.info("devtest screenshot {}: {}", name, msg.getString()));
	}
}
