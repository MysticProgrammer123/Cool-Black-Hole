package com.prof.blackhole.client.render;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.item.BlackHoleItems;
import com.prof.blackhole.physics.Relativity;
import com.prof.blackhole.world.HawkingRadiation;
import com.prof.blackhole.world.SpacetimeCorruption;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * What the Event Horizon Goggles show, drawn after the lensing pass so it is not bent itself:
 * <ul>
 *     <li><b>Field lines</b> - the radial lines of the gravitational field, with pulses running along them in the
 *     direction of the force (inward to a black hole, outward from a white hole), brightest where the field is
 *     strongest;</li>
 *     <li><b>Radii</b>, as rings in the equatorial plane - the horizon (magenta; for a white hole, its gold core),
 *     the photon sphere 1.5 r_s (gold), the ISCO (cyan), the 2 r_s time-stasis or time-acceleration sphere (blue /
 *     green), the radiation zone of a quiescent hole (lime), the corruption zone (grey violet) and the reach of
 *     gravity (faint white);</li>
 *     <li>the <b>spin axis</b> of a rotating hole (magenta).</li>
 * </ul>
 * The shader adds the rest: a celestial grid warped by the lens, the photon ring outlined, and a see-through disk.
 */
public final class GogglesVision {
	private static final double RANGE = 192.0;
	private static final int FIELD_LINES = 26;
	private static final int LINE_SEGMENTS = 18;
	private static final int RING_SEGMENTS = 96;
	/** Ribbon width per block of distance from the camera (about two pixels at 1080p). */
	private static final float WIDTH_PER_BLOCK = 0.0035f;
	private static final float[] CYAN = {0.3f, 0.85f, 1.0f};
	private static final float[] GOLD = {1.0f, 0.78f, 0.32f};
	private static final float[] MAGENTA = {1.0f, 0.3f, 0.85f};
	private static final float[] BLUE = {0.35f, 0.5f, 1.0f};
	private static final float[] GREEN = {0.4f, 1.0f, 0.5f};
	private static final float[] LIME = {0.6f, 1.0f, 0.3f};
	private static final float[] ASH = {0.65f, 0.55f, 0.75f};
	private static final float[] WHITE = {1.0f, 1.0f, 1.0f};

	private GogglesVision() {
	}

	public static boolean isWorn(MinecraftClient client) {
		return client.player != null && client.player.getEquippedStack(EquipmentSlot.HEAD).isOf(BlackHoleItems.EVENT_HORIZON_GOGGLES);
	}

	public static void render(Camera camera, Matrix4f viewRotation, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || ClientBlackHoleState.isEmpty() || !isWorn(client)) {
			return;
		}
		Vec3d cam = camera.getPos();
		long worldTime = client.world.getTime();
		float time = (worldTime + tickDelta) / 20.0f;
		HoleGeometry.draw(viewRotation, HoleGeometry.GLOW, builder -> {
			for (BlackHoleData hole : ClientBlackHoleState.all()) {
				if (hole.pos().distanceTo(cam) > RANGE + hole.influenceRadius()) {
					continue;
				}
				Vector3f c = hole.pos().subtract(cam).toVector3f();
				fieldLines(builder, hole, c, time);
				rings(builder, hole, c, worldTime);
				if (hole.spin() > 0.05) {
					float length = (float) (3.0 * hole.schwarzschildRadius());
					Vector3f top = new Vector3f(c).add(0.0f, length, 0.0f);
					Vector3f bottom = new Vector3f(c).sub(0.0f, length, 0.0f);
					builder.ribbon(bottom, top, width(c), MAGENTA, 0.8f, MAGENTA, 0.8f);
				}
			}
		});
	}

	private static float width(Vector3f relative) {
		return Math.max(relative.length() * WIDTH_PER_BLOCK, 0.01f);
	}

	private static void fieldLines(HoleGeometry.Builder builder, BlackHoleData hole, Vector3f c, float time) {
		double rs = hole.schwarzschildRadius();
		double inner = hole.horizonRadius() * 1.08;
		double outer = Math.min(hole.influenceRadius(), 20.0 * rs + 10.0);
		if (outer <= inner) {
			return;
		}
		float[] color = hole.white() ? GOLD : CYAN;
		// Pulses travel in the direction of the force.
		double flow = hole.white() ? -1.6 : 1.6;
		double golden = Math.PI * (3.0 - Math.sqrt(5.0));
		double logRatio = Math.log(outer / inner);
		for (int k = 0; k < FIELD_LINES; k++) {
			double y = 1.0 - 2.0 * (k + 0.5) / FIELD_LINES;
			double rho = Math.sqrt(1.0 - y * y);
			double phi = golden * k;
			Vector3f dir = new Vector3f((float) (rho * Math.cos(phi)), (float) y, (float) (rho * Math.sin(phi)));
			for (int i = 0; i < LINE_SEGMENTS; i++) {
				double r0 = inner * Math.exp(logRatio * i / LINE_SEGMENTS);
				double r1 = inner * Math.exp(logRatio * (i + 1) / LINE_SEGMENTS);
				Vector3f a = new Vector3f(dir).mul((float) r0).add(c);
				Vector3f b = new Vector3f(dir).mul((float) r1).add(c);
				float strength = (float) Math.pow(inner / r0, 0.6);
				double phase = Math.log(r0) * 2.5 + time * flow + k * 0.37;
				float pulse = (float) (0.25 + 0.75 * Math.pow(0.5 + 0.5 * Math.cos(phase * 2.0 * Math.PI / 3.0), 6.0));
				float alpha = Math.min(0.85f * strength * pulse + 0.08f, 1.0f);
				builder.ribbon(a, b, width(a), color, alpha, color, alpha * 0.9f);
			}
		}
	}

	private static void rings(HoleGeometry.Builder builder, BlackHoleData hole, Vector3f c, long worldTime) {
		double rs = hole.schwarzschildRadius();
		if (hole.white()) {
			ring(builder, c, hole.horizonRadius(), GOLD, 0.95f, false);
			ring(builder, c, 2.0 * rs, GREEN, 0.6f, true);
		} else {
			ring(builder, c, hole.horizonRadius(), MAGENTA, 0.95f, false);
			ring(builder, c, Relativity.photonSphere(rs), GOLD, 0.85f, false);
			ring(builder, c, Relativity.iscoRadius(rs, hole.spin()), CYAN, 0.75f, false);
			ring(builder, c, 2.0 * rs, BLUE, 0.6f, true);
			if (HawkingRadiation.radiates(hole) && !ClientBlackHoleState.isFeeding(hole.id())) {
				ring(builder, c, HawkingRadiation.zoneRadius(hole), LIME, 0.6f, true);
			}
			double corruption = SpacetimeCorruption.radius(hole, worldTime);
			if (corruption > 0.0) {
				ring(builder, c, corruption, ASH, 0.5f, true);
			}
		}
		ring(builder, c, hole.influenceRadius(), WHITE, 0.22f, true);
	}

	/** A horizontal circle around the hole; dashed ones mark zones rather than surfaces. */
	private static void ring(HoleGeometry.Builder builder, Vector3f c, double radius, float[] color, float alpha, boolean dashed) {
		for (int i = 0; i < RING_SEGMENTS; i++) {
			if (dashed && (i & 2) != 0) {
				continue;
			}
			double a0 = 2.0 * Math.PI * i / RING_SEGMENTS;
			double a1 = 2.0 * Math.PI * (i + 1) / RING_SEGMENTS;
			Vector3f p = new Vector3f(c).add((float) (radius * Math.sin(a0)), 0.0f, (float) (radius * Math.cos(a0)));
			Vector3f q = new Vector3f(c).add((float) (radius * Math.sin(a1)), 0.0f, (float) (radius * Math.cos(a1)));
			builder.ribbon(p, q, width(p), color, alpha, color, alpha);
		}
	}
}
