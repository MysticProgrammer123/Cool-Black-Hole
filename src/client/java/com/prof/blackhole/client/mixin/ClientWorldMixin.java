package com.prof.blackhole.client.mixin;

import com.prof.blackhole.client.GravityEnvironment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Within ~100 blocks of a black hole the sky fades to the black of space, even at noon, and the stars come out; the
 * lensing pass then warps them around the hole. Clouds darken with the sky. Near a white hole the sky is instead
 * washed out toward the pale gold-white of its glare.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
	@Unique
	private static final float CLOUD_DARKENING = 0.85F;
	@Unique
	private static final Vec3d GLARE = new Vec3d(1.0, 0.95, 0.85);
	@Unique
	private static final float MAX_GLARE = 0.7F;

	@Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
	private void profs_black_hole$darkenSky(Vec3d cameraPos, float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
		float darkness = GravityEnvironment.skyDarkness(cameraPos);
		float glare = GravityEnvironment.skyGlare(cameraPos);
		if (darkness > 0.0F || glare > 0.0F) {
			Vec3d color = cir.getReturnValue().multiply(1.0F - darkness);
			cir.setReturnValue(color.lerp(GLARE, MAX_GLARE * glare));
		}
	}

	@Inject(method = "getCloudsColor", at = @At("RETURN"), cancellable = true)
	private void profs_black_hole$darkenClouds(float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
		float darkness = GravityEnvironment.skyDarkness();
		if (darkness > 0.0F) {
			cir.setReturnValue(cir.getReturnValue().multiply(1.0F - CLOUD_DARKENING * darkness));
		}
	}

	@Inject(method = "getStarBrightness", at = @At("RETURN"), cancellable = true)
	private void profs_black_hole$showStars(float tickDelta, CallbackInfoReturnable<Float> cir) {
		float darkness = GravityEnvironment.skyDarkness();
		if (darkness > cir.getReturnValueF()) {
			cir.setReturnValue(darkness);
		}
	}
}
