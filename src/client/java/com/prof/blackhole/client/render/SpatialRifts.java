package com.prof.blackhole.client.render;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.client.ClientConfig;
import com.prof.blackhole.data.BlackHoleData;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Spatial rift lines: short, jagged cracks of light that flicker into existence around a hole, as if spacetime were
 * tearing along the strained field. Each rift is an arc of a random great circle between 1.6 and 4 r_s, broken into
 * kinked segments, living about a second; feeding holes crack more often. Violet and cyan around black holes, gold
 * and white around white holes.
 * <p>
 * They are drawn into the scene before the lensing pass, so the hole bends them like everything else.
 */
public final class SpatialRifts {
	private static final int MAX_RIFTS = 48;
	private static final double RANGE = 128.0;
	private static final double RATE = 0.08;
	private static final double FEEDING_RATE = 0.12;
	private static final int POINTS = 10;
	private static final float[][] BLACK_COLORS = {{0.66f, 0.42f, 1.0f}, {0.42f, 0.86f, 1.0f}};
	private static final float[][] WHITE_COLORS = {{1.0f, 0.86f, 0.5f}, {1.0f, 1.0f, 1.0f}};

	private record Rift(String holeId, Vec3d[] points, long born, int life, float width, float[] color, float phase) {
	}

	private static final List<Rift> RIFTS = new ArrayList<>();

	private SpatialRifts() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(SpatialRifts::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.world == null) {
			RIFTS.clear();
			return;
		}
		if (client.isPaused()) {
			return;
		}
		long time = client.world.getTime();
		for (Iterator<Rift> it = RIFTS.iterator(); it.hasNext(); ) {
			Rift rift = it.next();
			if (time - rift.born > rift.life || ClientBlackHoleState.byId(rift.holeId) == null) {
				it.remove();
			}
		}
		double budget = ClientConfig.particles() * switch (client.options.getParticles().getValue()) {
			case ALL -> 1.0;
			case DECREASED -> 0.5;
			case MINIMAL -> 0.0;
		};
		if (budget <= 0.0) {
			return;
		}
		Vec3d cam = client.gameRenderer.getCamera().getPos();
		Random random = client.world.random;
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			if (hole.frozen() || RIFTS.size() >= MAX_RIFTS) {
				continue;
			}
			double rs = hole.schwarzschildRadius();
			if (hole.pos().distanceTo(cam) > RANGE + 4.0 * rs) {
				continue;
			}
			double rate = (ClientBlackHoleState.isFeeding(hole.id()) ? FEEDING_RATE + RATE : RATE) * budget;
			if (random.nextDouble() < rate) {
				RIFTS.add(create(hole, random, time));
			}
		}
	}

	private static Rift create(BlackHoleData hole, Random random, long time) {
		double rs = hole.schwarzschildRadius();
		double radius = rs * (1.6 + 2.4 * random.nextDouble());
		Vector3f normal = randomUnit(random);
		Vector3f helper = Math.abs(normal.y) < 0.9f ? new Vector3f(0.0f, 1.0f, 0.0f) : new Vector3f(1.0f, 0.0f, 0.0f);
		Vector3f u = normal.cross(helper, new Vector3f()).normalize();
		Vector3f v = normal.cross(u, new Vector3f()).normalize();
		double start = random.nextDouble() * 2.0 * Math.PI;
		double arc = 0.5 + 0.9 * random.nextDouble();
		Vec3d[] points = new Vec3d[POINTS];
		for (int i = 0; i < POINTS; i++) {
			double angle = start + arc * i / (POINTS - 1);
			double r = radius * (1.0 + 0.12 * (random.nextDouble() * 2.0 - 1.0));
			double lift = radius * 0.08 * (random.nextDouble() * 2.0 - 1.0);
			double c = Math.cos(angle);
			double s = Math.sin(angle);
			points[i] = new Vec3d(
				hole.x() + r * (u.x * c + v.x * s) + lift * normal.x,
				hole.y() + r * (u.y * c + v.y * s) + lift * normal.y,
				hole.z() + r * (u.z * c + v.z * s) + lift * normal.z);
		}
		float[][] palette = hole.white() ? WHITE_COLORS : BLACK_COLORS;
		return new Rift(hole.id(), points, time, 10 + random.nextInt(14), (float) (0.025 * rs + 0.03),
			palette[random.nextInt(palette.length)], random.nextFloat() * 6.0f);
	}

	private static Vector3f randomUnit(Random random) {
		double y = random.nextDouble() * 2.0 - 1.0;
		double phi = random.nextDouble() * 2.0 * Math.PI;
		double rho = Math.sqrt(1.0 - y * y);
		return new Vector3f((float) (rho * Math.cos(phi)), (float) y, (float) (rho * Math.sin(phi)));
	}

	/** Draws the living rifts (from {@code WorldRendererMixin}, before the lensing pass). */
	public static void render(Camera camera, Matrix4f viewRotation, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (RIFTS.isEmpty() || client.world == null) {
			return;
		}
		Vec3d cam = camera.getPos();
		float now = client.world.getTime() + tickDelta;
		HoleGeometry.draw(viewRotation, HoleGeometry.GLOW, builder -> {
			for (Rift rift : RIFTS) {
				float age = now - rift.born;
				float life = Math.max(1.0f - age / rift.life, 0.0f);
				float flicker = 0.55f + 0.45f * (float) Math.sin(age * 1.9f + rift.phase);
				float alpha = life * flicker;
				if (alpha <= 0.01f) {
					continue;
				}
				for (int i = 0; i + 1 < rift.points.length; i++) {
					Vector3f a = rift.points[i].subtract(cam).toVector3f();
					Vector3f b = rift.points[i + 1].subtract(cam).toVector3f();
					// A tapering core and a soft, wide halo.
					float taper = 1.0f - Math.abs(i - (rift.points.length - 2) * 0.5f) / rift.points.length;
					builder.ribbon(a, b, rift.width * taper, rift.color, alpha, rift.color, alpha);
					builder.ribbon(a, b, rift.width * 4.0f * taper, rift.color, 0.18f * alpha, rift.color, 0.18f * alpha);
				}
			}
		});
	}
}
