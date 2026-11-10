package dev.needle.client.mixin;

import dev.needle.client.JukeboxSongs;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.item.JukeboxSong;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Jukebox start/stop level events, with the sound instance vanilla created for the song. */
@Mixin(LevelEventHandler.class)
abstract class LevelEventHandlerMixin {
	@Shadow
	@Final
	private Map<BlockPos, SoundInstance> playingJukeboxSongs;

	@Shadow
	@Final
	private ClientLevel level;

	// TAIL: playJukeboxSong first calls stopJukeboxSong for the same position, then stores the new instance
	@Inject(method = "playJukeboxSong", at = @At("TAIL"))
	private void needle$songStarted(Holder<JukeboxSong> song, BlockPos pos, CallbackInfo ci) {
		SoundInstance instance = playingJukeboxSongs.get(pos);
		if (instance != null) {
			JukeboxSongs.started(level, pos.immutable(), song, instance);
		}
	}

	@Inject(method = "stopJukeboxSong", at = @At("HEAD"))
	private void needle$songStopped(BlockPos pos, CallbackInfo ci) {
		JukeboxSongs.stopped(level, pos);
	}
}
