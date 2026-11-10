package dev.needle.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game music track playing right now (null between songs). */
@Mixin(MusicManager.class)
public interface MusicManagerAccessor {
	@Accessor("currentMusic")
	SoundInstance needle$currentMusic();
}
