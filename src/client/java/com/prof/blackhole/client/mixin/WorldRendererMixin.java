package com.prof.blackhole.client.mixin;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.client.render.BlackHoleRenderer;
import com.prof.blackhole.client.render.FallbackHoleRenderer;
import com.prof.blackhole.client.render.GogglesVision;
import com.prof.blackhole.client.render.SpatialRifts;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the holes' rendering onto the end of world rendering, in order: the spatial rifts (drawn into the scene, so
 * they get lensed), the lensing passes or, without the shader, the fallback geometry, and last the Event Horizon
 * Goggles overlay (not lensed). GameRenderer.renderWorld then draws the hand on top, so the first-person hand is
 * never lensed.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Unique
	private static boolean profs_black_hole$overlayFailed;

	@Inject(method = "render", at = @At("HEAD"))
	private void profs_black_hole$beginWorldPass(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera,
			GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f viewRotation,
			Matrix4f projection, CallbackInfo ci) {
		BlackHoleRenderer.beginWorldPass();
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void profs_black_hole$renderBlackHole(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera,
			GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f viewRotation,
			Matrix4f projection, CallbackInfo ci) {
		BlackHoleRenderer.endWorldPass();
		float tickDelta = tickCounter.getTickDelta(false);
		profs_black_hole$overlay(() -> SpatialRifts.render(camera, viewRotation, tickDelta));
		if (BlackHoleRenderer.INSTANCE.isActive()) {
			BlackHoleRenderer.INSTANCE.render(camera, viewRotation, projection, tickDelta);
		} else {
			profs_black_hole$overlay(() -> FallbackHoleRenderer.render(camera, viewRotation, tickDelta));
		}
		profs_black_hole$overlay(() -> GogglesVision.render(camera, viewRotation, tickDelta));
	}

	/** The geometry overlays must never take the frame down; one that fails is logged once and skipped. */
	@Unique
	private static void profs_black_hole$overlay(Runnable draw) {
		try {
			draw.run();
		} catch (RuntimeException e) {
			if (!profs_black_hole$overlayFailed) {
				profs_black_hole$overlayFailed = true;
				ProfsBlackHole.LOGGER.error("Black Hole overlay rendering failed", e);
			}
		}
	}
}
