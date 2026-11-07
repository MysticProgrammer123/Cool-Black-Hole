package com.prof.blackhole.client.render;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Per-vertex gravitational effects on entity meshes, applied through a wrapping vertex consumer:
 * <ul>
 * <li><b>Gravitational redshift</b> of entities deeper in the well than the camera. Light climbing from radius r_e
 * to the camera at r_c arrives with frequency ratio {@code g = sqrt(1 - r_s/r_e) / sqrt(1 - r_s/r_c)}. For
 * {@code g < 1} the vertex colours are shifted toward red by {@code z' = 1 - g}: blue fades fastest, green half as
 * fast. Only redshift is drawn (a camera deeper than the entity would see blueshift), which keeps the tint
 * continuous at the edge of the influence sphere: an entity there is redshifted by only ~1.5%, so it fades in
 * smoothly as entities approach.</li>
 * <li><b>Mesh spaghettification.</b> The matrix stretch of {@code EntityRenderDispatcherMixin} is the linear
 * (first-order) tide. The tidal field steepens toward the hole (the pull goes as 1/r^2, so its gradient as 1/r^3),
 * so the near end of a body is stretched further than the far end: vertices on the hole side are drawn out along
 * the line to the singularity and pinched toward that axis, tapering the mesh into a noodle that points at the
 * hole.</li>
 * </ul>
 * Vertex positions reaching the consumer are camera-relative and world-axis aligned (the view rotation is applied
 * by the shader), so the deformation can be computed in world space.
 */
public final class GravitationalDistortion {
	/** Shift below which (about one 8-bit colour step) no tint is applied. */
	private static final double MIN_SHIFT = 0.004;
	/** Cap on z' = 1 - g, so the tint stays subtle even at the horizon (blue at 52%, green at 73%). */
	private static final double MAX_SHIFT = 0.6;
	private static final float GREEN_RESPONSE = 0.45f;
	private static final float BLUE_RESPONSE = 0.8f;
	/** Floor on the time dilation factor, which reaches 0 at r_s. */
	private static final double MIN_DILATION = 0.05;
	/** Linear stretch above which the mesh starts to taper. */
	private static final double MIN_TAPER_STRETCH = 1.05;
	/** Linear stretch at which the taper is at full strength (the visual cap). */
	private static final double FULL_TAPER_STRETCH = 6.0;
	/** At full strength the near end is drawn out by this fraction of the stretched half-length... */
	private static final float TAPER_EXTENSION = 0.6f;
	/** ...and pinched toward the axis by this fraction. */
	private static final float TAPER_PINCH = 0.65f;

	private GravitationalDistortion() {
	}

	/**
	 * The hole whose tides dominate at {@code center}: the largest r_s / r^3 among the holes (not frozen) whose
	 * influence reaches it, or null.
	 */
	@Nullable
	public static BlackHoleData dominant(Vec3d center) {
		BlackHoleData best = null;
		double bestTide = 0.0;
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			if (hole.frozen()) {
				continue;
			}
			double r = center.distanceTo(hole.pos());
			if (r < 1.0e-4 || r > hole.influenceRadius()) {
				continue;
			}
			double tide = hole.schwarzschildRadius() / (r * r * r);
			if (tide > bestTide) {
				bestTide = tide;
				best = hole;
			}
		}
		return best;
	}

	/** Returns {@code provider} wrapped to distort the entity, or {@code provider} itself when there is nothing to do. */
	public static VertexConsumerProvider apply(VertexConsumerProvider provider, Entity entity, float tickDelta, @Nullable Camera camera) {
		// GUI previews (inventory player, etc.) are not in the gravitational field.
		if (camera == null || !BlackHoleRenderer.isWorldPass() || ClientBlackHoleState.isEmpty()) {
			return provider;
		}
		float height = entity.getHeight();
		Vec3d entityCenter = entity.getLerpedPos(tickDelta).add(0.0, height * 0.5, 0.0);
		BlackHoleData hole = dominant(entityCenter);
		if (hole == null) {
			return provider;
		}

		double rs = hole.schwarzschildRadius();
		Vec3d holePos = hole.pos();
		Vec3d toHole = holePos.subtract(entityCenter);
		double entityR = toHole.length();

		Vec3d camPos = camera.getPos();
		double cameraR = camPos.distanceTo(holePos);
		float red = 1.0f;
		float green = 1.0f;
		float blue = 1.0f;
		boolean tint;
		if (hole.white()) {
			// Deeper in a white hole's (repulsive) well, clocks run fast: light from there arrives blueshifted.
			double g = Relativity.timeAccelerationFactor(rs, entityR) / Relativity.timeAccelerationFactor(rs, cameraR);
			double shift = Math.min(1.0 - 1.0 / Math.max(g, 1.0), MAX_SHIFT);
			tint = shift >= MIN_SHIFT;
			red = 1.0f - BLUE_RESPONSE * (float) shift;
			green = 1.0f - GREEN_RESPONSE * (float) shift;
		} else {
			double g = Math.max(Relativity.timeDilationFactor(rs, entityR), MIN_DILATION)
					/ Math.max(Relativity.timeDilationFactor(rs, cameraR), MIN_DILATION);
			double shift = Math.min(1.0 - g, MAX_SHIFT);
			tint = shift >= MIN_SHIFT;
			green = 1.0f - GREEN_RESPONSE * (float) shift;
			blue = 1.0f - BLUE_RESPONSE * (float) shift;
		}
		if (!tint) {
			red = 1.0f;
			green = 1.0f;
			blue = 1.0f;
		}

		// The first-person camera entity is not drawn stretched (see EntityRenderDispatcherMixin), so not tapered;
		// a white hole's tides flatten bodies rather than drawing them into noodles.
		boolean ownBody = entity == camera.getFocusedEntity() && !camera.isThirdPerson();
		double stretch = Relativity.tidalStretch(rs, entityR, height);
		boolean taper = !ownBody && !hole.white() && stretch > MIN_TAPER_STRETCH;
		if (!tint && !taper) {
			return provider;
		}

		Taper shape = null;
		if (taper) {
			float strength = (float) Math.min((stretch - MIN_TAPER_STRETCH) / (FULL_TAPER_STRETCH - MIN_TAPER_STRETCH), 1.0);
			Vec3d axis = toHole.multiply(1.0 / entityR);
			Vec3d c = entityCenter.subtract(camPos);
			shape = new Taper((float) c.x, (float) c.y, (float) c.z, (float) axis.x, (float) axis.y, (float) axis.z,
					(float) (0.5 * height * stretch), strength);
		}
		Taper finalShape = shape;
		float finalRed = red;
		float finalGreen = green;
		float finalBlue = blue;
		return layer -> new DistortingVertexConsumer(provider.getBuffer(layer), finalRed, finalGreen, finalBlue, finalShape);
	}

	/**
	 * Non-linear tidal taper about the entity centre {@code c} (camera-relative), along the unit axis {@code n} toward
	 * the hole. For a vertex at {@code q = v - c} with axial coordinate {@code u = (q . n) / halfLength}, the near side
	 * ({@code u > 0}) is pushed further along n by {@code TAPER_EXTENSION * strength * halfLength * u^2} and its
	 * distance from the axis scaled by {@code 1 - TAPER_PINCH * strength * min(u, 1)}.
	 */
	private record Taper(float cx, float cy, float cz, float nx, float ny, float nz, float halfLength, float strength) {
		void apply(float[] p) {
			float qx = p[0] - cx;
			float qy = p[1] - cy;
			float qz = p[2] - cz;
			float s = qx * nx + qy * ny + qz * nz;
			float u = s / halfLength;
			if (!(u > 0.0f)) {
				return;
			}
			float extend = TAPER_EXTENSION * strength * halfLength * u * u;
			float pinch = 1.0f - TAPER_PINCH * strength * Math.min(u, 1.0f);
			float px = qx - s * nx;
			float py = qy - s * ny;
			float pz = qz - s * nz;
			float axial = s + extend;
			p[0] = cx + px * pinch + nx * axial;
			p[1] = cy + py * pinch + ny * axial;
			p[2] = cz + pz * pinch + nz * axial;
		}
	}

	/** Tints and tapers every vertex; everything else passes straight through. */
	private static final class DistortingVertexConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final float red;
		private final float green;
		private final float blue;
		@Nullable
		private final Taper taper;
		private final float[] scratch = new float[3];

		DistortingVertexConsumer(VertexConsumer delegate, float red, float green, float blue, @Nullable Taper taper) {
			this.delegate = delegate;
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.taper = taper;
		}

		@Override
		public VertexConsumer vertex(float x, float y, float z) {
			if (taper != null) {
				scratch[0] = x;
				scratch[1] = y;
				scratch[2] = z;
				taper.apply(scratch);
				delegate.vertex(scratch[0], scratch[1], scratch[2]);
			} else {
				delegate.vertex(x, y, z);
			}
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			delegate.color(Math.round(red * this.red), Math.round(green * this.green), Math.round(blue * this.blue), alpha);
			return this;
		}

		@Override
		public VertexConsumer texture(float u, float v) {
			delegate.texture(u, v);
			return this;
		}

		@Override
		public VertexConsumer overlay(int u, int v) {
			delegate.overlay(u, v);
			return this;
		}

		@Override
		public VertexConsumer light(int u, int v) {
			delegate.light(u, v);
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			delegate.normal(x, y, z);
			return this;
		}

		/** Bulk path used by model parts; forwarded whole so the delegate keeps its fast implementation. */
		@Override
		public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
				float normalX, float normalY, float normalZ) {
			int r = Math.round(((color >>> 16) & 0xFF) * red);
			int g = Math.round(((color >>> 8) & 0xFF) * green);
			int b = Math.round((color & 0xFF) * blue);
			if (taper != null) {
				scratch[0] = x;
				scratch[1] = y;
				scratch[2] = z;
				taper.apply(scratch);
				x = scratch[0];
				y = scratch[1];
				z = scratch[2];
			}
			delegate.vertex(x, y, z, (color & 0xFF000000) | (r << 16) | (g << 8) | b, u, v, overlay, light, normalX, normalY, normalZ);
		}
	}
}
