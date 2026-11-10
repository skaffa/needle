package dev.needle.client;

import dev.needle.core.ListenTracker;
import dev.needle.core.NeedleConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Jukebox songs this client was told about. The server only sends start and stop events to players
 * nearby, so a song started while the player was far away is never here, and a stop may be missed when
 * the player walked away first; the song length is the fallback end.
 */
public final class JukeboxSongs {
	/** A just-started streaming sound can take a moment before the sound engine reports it active. */
	private static final long START_GRACE_MS = 3_000;
	/** Matches the end padding the server uses before it stops a jukebox. */
	private static final long END_GRACE_MS = 5_000;

	private record Playing(ClientLevel level, BlockPos pos, Holder<JukeboxSong> song, SoundInstance instance,
			long startedAtMs) {
	}

	private static final Map<BlockPos, Playing> PLAYING = new HashMap<>();

	private JukeboxSongs() {
	}

	public static void started(ClientLevel level, BlockPos pos, Holder<JukeboxSong> song, SoundInstance instance) {
		PLAYING.put(pos, new Playing(level, pos, song, instance, System.currentTimeMillis()));
	}

	public static void stopped(ClientLevel level, BlockPos pos) {
		Playing p = PLAYING.get(pos);
		if (DevTest.ON) {
			NeedleClient.LOG.info("devtest: stop event at {} (known {})", pos, p != null);
		}
		if (p != null && p.level == level) {
			PLAYING.remove(pos);
		}
	}

	public static void clear() {
		PLAYING.clear();
	}

	/** Adds one source per playing jukebox in the player's level and drops songs that have ended. */
	static void collect(Minecraft mc, NeedleConfig cfg, double master, List<ListenTracker.Source> out) {
		long nowMs = System.currentTimeMillis();
		double records = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
		Vec3 ear = mc.player.getEyePosition();
		for (Iterator<Playing> it = PLAYING.values().iterator(); it.hasNext(); ) {
			Playing p = it.next();
			long lengthMs = (long) (p.song.value().lengthInSeconds() * 1000);
			boolean active = mc.getSoundManager().isActive(p.instance) || nowMs - p.startedAtMs < START_GRACE_MS;
			if (p.level != mc.level || !active || nowMs > p.startedAtMs + lengthMs + END_GRACE_MS) {
				if (DevTest.ON) {
					NeedleClient.LOG.info("devtest: dropped jukebox at {} (other level {}, active {}, age {} ms)", p.pos,
							p.level != mc.level, active, nowMs - p.startedAtMs);
				}
				it.remove();
				continue;
			}
			ListenTracker.Track track = VanillaTracks.jukebox(mc, p.song, p.instance);
			if (track == null) {
				continue;
			}
			double distance = Math.sqrt(ear.distanceToSqr(Vec3.atCenterOf(p.pos)));
			boolean audible = !mc.isPaused() && distance <= cfg.listenRadius && cfg.volumeAllows(master, records, true);
			out.add(new ListenTracker.Source(track, ListenTracker.Kind.JUKEBOX, audible, distance, p.startedAtMs / 1000));
		}
	}
}
