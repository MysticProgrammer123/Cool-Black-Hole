package com.prof.blackhole.client.mixin;

import com.prof.blackhole.client.GravityEnvironment;
import java.util.Map;
import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.client.sound.TickableSoundInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gravitational time dilation of sound: every sound is played slowed and lowered by the listener's dtau/dt
 * ({@link GravityEnvironment#pitchFactor}). OpenAL's pitch is a playback rate, so pitch and tempo drop together.
 * New sounds pick it up when they start; sounds already playing (music, ambient loops) are retuned whenever the
 * factor changes. Tickable sounds are retuned by vanilla every tick anyway.
 */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {
	@Unique
	private static final float RETUNE_THRESHOLD = 0.003F;

	@Shadow
	@Final
	private Map<SoundInstance, Channel.SourceManager> sources;

	@Unique
	private float profs_black_hole$appliedFactor = 1.0F;

	@Shadow
	protected abstract float getAdjustedPitch(SoundInstance sound);

	@Inject(method = "getAdjustedPitch", at = @At("RETURN"), cancellable = true)
	private void profs_black_hole$dilatePitch(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(GravityEnvironment.dilatePitch(sound, cir.getReturnValueF()));
	}

	@Inject(method = "tick()V", at = @At("TAIL"))
	private void profs_black_hole$retunePlaying(CallbackInfo ci) {
		float factor = GravityEnvironment.pitchFactor();
		if (Math.abs(factor - profs_black_hole$appliedFactor) < RETUNE_THRESHOLD) {
			return;
		}
		profs_black_hole$appliedFactor = factor;
		for (var entry : sources.entrySet()) {
			SoundInstance sound = entry.getKey();
			if (sound instanceof TickableSoundInstance) {
				continue;
			}
			float pitch = getAdjustedPitch(sound);
			entry.getValue().run(source -> source.setPitch(pitch));
		}
	}
}
