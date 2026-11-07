package com.prof.blackhole.client.mixin;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.client.render.BlackHoleRenderer;
import com.prof.blackhole.client.render.GravitationalDistortion;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spaghettification: stretches entity meshes along the direction to the black hole by the tidal factor
 * {@code s ∝ M / r^3} while compressing the perpendicular axes by {@code 1/sqrt(s)} (volume preserving).
 * <p>
 * At the injection point the matrix stack is camera-relative and world-axis-aligned, translated to the entity's
 * render origin; the entity renderer applies its own yaw/body rotations afterwards, so the distortion stays fixed
 * to the world-space pull direction regardless of how the entity is facing.
 * <p>
 * The same call also receives a vertex consumer provider that adds redshift and the non-linear tidal taper
 * ({@link GravitationalDistortion}).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Unique
	private static final String ENTITY_RENDERER_RENDER =
			"Lnet/minecraft/client/render/entity/EntityRenderer;render(Lnet/minecraft/entity/Entity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V";

	@Shadow
	public Camera camera;

	@Inject(method = "render", at = @At(value = "INVOKE", target = ENTITY_RENDERER_RENDER))
	private void profs_black_hole$pushTidalStretch(Entity entity, double x, double y, double z, float yaw, float tickDelta,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		// Always push so the matching pop below stays balanced, whether or not a distortion is applied.
		matrices.push();
		profs_black_hole$applyTidalStretch(entity, tickDelta, matrices);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = ENTITY_RENDERER_RENDER, shift = At.Shift.AFTER))
	private void profs_black_hole$popTidalStretch(Entity entity, double x, double y, double z, float yaw, float tickDelta,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		matrices.pop();
	}

	/** Redshift and tidal taper: the entity renderer draws through a distorting wrapper of the vertex consumers. */
	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = ENTITY_RENDERER_RENDER), index = 4)
	private VertexConsumerProvider profs_black_hole$gravitationalDistortion(Entity entity, float yaw, float tickDelta,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		return GravitationalDistortion.apply(vertexConsumers, entity, tickDelta, this.camera);
	}

	@Unique
	private void profs_black_hole$applyTidalStretch(Entity entity, float tickDelta, MatrixStack matrices) {
		// GUI previews (inventory player, etc.) use a non-world-aligned matrix stack; only distort in the world pass.
		if (!BlackHoleRenderer.isWorldPass() || ClientBlackHoleState.isEmpty()) {
			return;
		}
		Camera cam = this.camera;
		if (cam != null && entity == cam.getFocusedEntity() && !cam.isThirdPerson()) {
			return;
		}

		float height = entity.getHeight();
		Vec3d center = entity.getLerpedPos(tickDelta).add(0.0, height * 0.5, 0.0);
		BlackHoleData hole = GravitationalDistortion.dominant(center);
		if (hole == null) {
			return;
		}
		double rs = hole.schwarzschildRadius();
		Vec3d toHole = hole.pos().subtract(center);
		double distance = toHole.length();

		float stretch = (float) Relativity.tidalStretch(rs, distance, height);
		if (!(stretch > 1.0001f)) {
			return;
		}
		if (hole.white()) {
			// A negative mass's tides compress along the line to it and stretch across it: bodies flatten.
			stretch = 1.0f / stretch;
		}

		float inv = (float) (1.0 / distance);
		Quaternionf align = new Quaternionf().rotationTo(0.0f, 1.0f, 0.0f,
				(float) toHole.x * inv, (float) toHole.y * inv, (float) toHole.z * inv);
		float squeeze = 1.0f / (float) Math.sqrt(stretch);
		float halfHeight = height * 0.5f;

		matrices.translate(0.0f, halfHeight, 0.0f);
		matrices.multiply(align);
		matrices.scale(squeeze, stretch, squeeze);
		matrices.multiply(align.conjugate());
		matrices.translate(0.0f, -halfHeight, 0.0f);
	}
}
