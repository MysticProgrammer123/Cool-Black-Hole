package com.prof.blackhole.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prof.blackhole.client.GravityEnvironment;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The fog (and the clear colour behind the sky) darkens with the sky near a black hole, and pales with it near a
 * white hole, unless underwater or in lava.
 */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
	@Shadow
	private static float red;
	@Shadow
	private static float green;
	@Shadow
	private static float blue;

	@Inject(method = "render", at = @At("RETURN"))
	private static void profs_black_hole$darkenFog(Camera camera, float tickDelta, ClientWorld world, int viewDistance,
			float skyDarkness, CallbackInfo ci) {
		if (camera.getSubmersionType() != CameraSubmersionType.NONE) {
			return;
		}
		float darkness = GravityEnvironment.skyDarkness(camera.getPos());
		float glare = 0.7F * GravityEnvironment.skyGlare(camera.getPos());
		if (darkness <= 0.0F && glare <= 0.0F) {
			return;
		}
		float keep = 1.0F - darkness;
		red = red * keep * (1.0F - glare) + 1.0F * glare;
		green = green * keep * (1.0F - glare) + 0.95F * glare;
		blue = blue * keep * (1.0F - glare) + 0.85F * glare;
		RenderSystem.clearColor(red, green, blue, 0.0F);
	}
}
