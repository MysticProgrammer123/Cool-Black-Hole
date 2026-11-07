package com.prof.blackhole.client.render;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.client.particle.DiskColors;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Holes without the lensing shader (switched off in the control panel, failed to build, or displaced by a shader
 * pack): plain 3D geometry that costs next to nothing. A black hole is its shadow (an opaque black sphere the size of
 * the photon-capture radius, ~2.6 M), ringed by a bright photon ring and orange glow, with a flat accretion disk
 * whose blackbody colour follows the shader's temperature profile and whose approaching side is brighter. A white
 * hole is a blinding white-gold core in a wide halo, with a blue-white ejection disk. The particle effects still
 * play on top.
 */
public final class FallbackHoleRenderer {
	private static final double RANGE_MARGIN = 64.0;
	private static final int DISK_SEGMENTS = 64;
	private static final int DISK_RINGS = 8;
	private static final float[] RING_ORANGE = {1.0f, 0.72f, 0.38f};
	private static final float[] GLOW_ORANGE = {1.0f, 0.5f, 0.2f};
	private static final float[] CORE_WHITE = {1.0f, 0.97f, 0.9f};
	private static final float[] CORE_GOLD = {1.0f, 0.78f, 0.4f};
	private static final float[] WHITE_TINT = {0.55f, 0.9f, 1.0f};

	private FallbackHoleRenderer() {
	}

	public static void render(Camera camera, Matrix4f viewRotation, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || ClientBlackHoleState.isEmpty()) {
			return;
		}
		Vec3d cam = camera.getPos();
		double range = client.options.getClampedViewDistance() * 16.0 + RANGE_MARGIN;
		List<BlackHoleData> holes = new ArrayList<>();
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			if (hole.pos().distanceTo(cam) < range + 6.0 * hole.schwarzschildRadius()) {
				holes.add(hole);
			}
		}
		if (holes.isEmpty()) {
			return;
		}
		HoleGeometry.draw(viewRotation, HoleGeometry.SOLID, builder -> {
			for (BlackHoleData hole : holes) {
				Vector3f c = relative(hole, cam);
				float rs = (float) hole.schwarzschildRadius();
				if (hole.white()) {
					builder.sphere(c.x, c.y, c.z, 0.7f * rs, CORE_WHITE[0], CORE_WHITE[1], CORE_WHITE[2], 1.0f);
				} else {
					// The shadow: photon capture radius sqrt(27) M ~ 2.6 M, a little smaller for fast spin.
					float shadow = (float) (1.3 * rs * (1.0 - 0.1 * hole.spin() * hole.spin()));
					builder.sphere(c.x, c.y, c.z, shadow, 0.0f, 0.0f, 0.0f, 1.0f);
				}
			}
		});
		long time = client.world.getTime();
		HoleGeometry.draw(viewRotation, HoleGeometry.GLOW, builder -> {
			for (BlackHoleData hole : holes) {
				Vector3f c = relative(hole, cam);
				float rs = (float) hole.schwarzschildRadius();
				if (hole.white()) {
					builder.billboardRing(c, 0.7f * rs, 1.1f * rs, CORE_WHITE, 0.9f, CORE_GOLD, 0.45f);
					builder.billboardRing(c, 1.1f * rs, 4.0f * rs, CORE_GOLD, 0.45f, CORE_GOLD, 0.0f);
				} else {
					float shadow = (float) (1.3 * rs * (1.0 - 0.1 * hole.spin() * hole.spin()));
					builder.billboardRing(c, shadow, shadow * 1.09f, RING_ORANGE, 0.95f, RING_ORANGE, 0.6f);
					builder.billboardRing(c, shadow * 1.09f, shadow * 1.8f, GLOW_ORANGE, 0.35f, GLOW_ORANGE, 0.0f);
				}
				if (hole.accretionDisk()) {
					disk(builder, hole, c, rs, time + tickDelta);
				}
			}
		});
	}

	/**
	 * A flat annulus in the equatorial plane, coloured like the shader's disk: blackbody colour of the local
	 * temperature, brighter where the gas comes toward the camera (Doppler beaming).
	 */
	private static void disk(HoleGeometry.Builder builder, BlackHoleData hole, Vector3f c, float rs, float time) {
		double inner = hole.white() ? 2.0 * rs : Math.max(2.0 * rs, Relativity.iscoRadius(rs, hole.spin()));
		double outer = 6.0 * rs;
		double spinPhase = time * 0.02 * (hole.white() ? -1.0 : 1.0);
		Vector3f toCamera = new Vector3f(c).negate();
		for (int ring = 0; ring < DISK_RINGS; ring++) {
			double r0 = inner + (outer - inner) * ring / DISK_RINGS;
			double r1 = inner + (outer - inner) * (ring + 1) / DISK_RINGS;
			float[] col0 = color(hole, r0 / inner);
			float[] col1 = color(hole, r1 / inner);
			float a0 = alpha(ring, DISK_RINGS);
			float a1 = alpha(ring + 1, DISK_RINGS);
			for (int seg = 0; seg < DISK_SEGMENTS; seg++) {
				double p0 = 2.0 * Math.PI * seg / DISK_SEGMENTS + spinPhase;
				double p1 = 2.0 * Math.PI * (seg + 1) / DISK_SEGMENTS + spinPhase;
				float beam0 = beaming(hole, p0, toCamera);
				float beam1 = beaming(hole, p1, toCamera);
				diskVertex(builder, c, r0, p0, col0, a0 * beam0);
				diskVertex(builder, c, r1, p0, col1, a1 * beam0);
				diskVertex(builder, c, r1, p1, col1, a1 * beam1);
				diskVertex(builder, c, r0, p1, col0, a0 * beam1);
			}
		}
	}

	/** Fades in over the inner edge and out over the outer third, like the shader's disk. */
	private static float alpha(int ring, int rings) {
		float t = ring / (float) rings;
		return 0.9f * Math.min(1.0f, t * 6.0f + 0.25f) * Math.min(1.0f, (1.0f - t) * 3.0f);
	}

	private static float[] color(BlackHoleData hole, double rOverInner) {
		double tRel = Math.pow(rOverInner, -0.75);
		float[] rgb = DiskColors.blackbody(tRel * hole.temperature());
		if (hole.white()) {
			for (int i = 0; i < 3; i++) {
				rgb[i] = rgb[i] * 0.55f + WHITE_TINT[i] * 0.45f;
			}
		}
		return rgb;
	}

	/** Prograde gas at angle phi moves along (cos phi, 0, -sin phi); toward the camera it is brighter. */
	private static float beaming(BlackHoleData hole, double phi, Vector3f toCamera) {
		float length = toCamera.length();
		if (!(length > 1.0e-4f)) {
			return 1.0f;
		}
		double sign = hole.white() ? -1.0 : 1.0;
		double approach = sign * (Math.cos(phi) * toCamera.x - Math.sin(phi) * toCamera.z) / length;
		return (float) Math.max(0.15, 1.0 + (0.4 + 0.6 * hole.spin()) * approach);
	}

	private static void diskVertex(HoleGeometry.Builder builder, Vector3f c, double r, double phi, float[] color, float alpha) {
		builder.vertex(c.x + (float) (r * Math.sin(phi)), c.y, c.z + (float) (r * Math.cos(phi)), color[0], color[1], color[2],
			Math.min(alpha, 1.0f));
	}

	private static Vector3f relative(BlackHoleData hole, Vec3d cam) {
		return new Vector3f((float) (hole.x() - cam.x), (float) (hole.y() - cam.y), (float) (hole.z() - cam.z));
	}
}
