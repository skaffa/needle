package dev.needle.client;

import dev.needle.core.ListenTracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Optional one-line HUD (config {@code showHud}): the track that is gaining listening time. */
final class NeedleHud {
	private NeedleHud() {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!NeedleClient.config.showHud || !NeedleClient.config.enabled) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ListenTracker.ListenView focus = NeedleClient.tracker.focus();
		if (focus == null) {
			return;
		}
		String progress = focus.scrobbled() ? "  ✓"
				: focus.requiredMs() < 0 ? ""
				: "  " + NeedleClient.time(focus.listenedMs()) + " / " + NeedleClient.time(focus.requiredMs());
		String line = "♪ " + focus.track().name() + progress;
		int w = mc.font.width(line);
		g.fill(2, 2, 8 + w, 15, 0x90101010);
		g.text(mc.font, line, 5, 5, focus.scrobbled() ? 0xFF80FF80 : 0xFFFFFFFF, true);
	}
}
