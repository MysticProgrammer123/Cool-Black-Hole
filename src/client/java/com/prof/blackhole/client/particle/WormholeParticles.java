package com.prof.blackhole.client.particle;

import com.prof.blackhole.client.ClientWormholeState;
import com.prof.blackhole.network.WormholeSyncPayload.Mouth;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * The light of wormholes, from the mouths the server syncs ({@link ClientWormholeState}). White holes themselves
 * are drawn by {@link BlackHoleParticles} (and the lensing shader), whether or not a wormhole leads to them.
 * <ul>
 *     <li><b>The wormhole beam</b> - when both mouths are in one dimension, a twin-stranded helix of energy flowing
 *     from the black hole to the white hole, crackling with sparks, drawn along the stretch nearest the camera.</li>
 *     <li><b>Tunnels</b> - when the mouths are in different dimensions, each opens a spiralling funnel to the sky:
 *     light pours down into the black hole and streams up out of the white hole.</li>
 * </ul>
 */
final class WormholeParticles {
	/** Mouths farther than this from the camera are not drawn, blocks. */
	private static final double DRAW_RANGE = 192.0;
	/** The beam is drawn this far either side of the point nearest the camera, blocks. */
	private static final double BEAM_WINDOW = 96.0;
	private static final double TUNNEL_HEIGHT = 64.0;
	private static final float[] WHITE = {1.0f, 1.0f, 1.0f};
	private static final float[] CYAN = {0.5f, 0.95f, 1.0f};
	private static final float[] VIOLET = {0.72f, 0.42f, 1.0f};

	private WormholeParticles() {
	}

	static void tick(ParticleManager particles, ClientWorld world, Sprite[] sprites, Random random, double budget, Vec3d camera) {
		long time = world.getTime();
		for (Mouth mouth : ClientWormholeState.get()) {
			boolean near = mouth.pos().distanceTo(camera) < DRAW_RANGE;
			if (mouth.partner().isPresent()) {
				// Drawn once per pair, from the black hole end, in the direction matter flows.
				if (!mouth.white()) {
					beam(particles, world, sprites, random, budget, mouth.pos(), mouth.partner().get(), camera, time);
				}
			} else if (near) {
				tunnel(particles, world, sprites, random, budget, mouth.pos(), mouth.white(), time);
			}
		}
	}

	/** A twin-stranded helix of energy from the black hole {@code from} to the white hole {@code to}. */
	private static void beam(ParticleManager particles, ClientWorld world, Sprite[] sprites, Random random, double budget,
			Vec3d from, Vec3d to, Vec3d camera, long time) {
		Vec3d axis = to.subtract(from);
		double length = axis.length();
		if (length < 1.0e-3) {
			return;
		}
		Vec3d dir = axis.multiply(1.0 / length);
		double nearest = MathHelper.clamp(camera.subtract(from).dotProduct(dir), 0.0, length);
		if (from.add(dir.multiply(nearest)).distanceTo(camera) > DRAW_RANGE) {
			return;
		}
		double start = Math.max(nearest - BEAM_WINDOW, 0.0);
		double end = Math.min(nearest + BEAM_WINDOW, length);
		Vec3d[] basis = perpendicularBasis(dir);
		int count = count(16.0 * budget, random);
		for (int i = 0; i < count; i++) {
			double s = start + random.nextDouble() * (end - start);
			int strand = i & 1;
			double angle = s * 0.35 - time * 0.25 + strand * Math.PI;
			Vec3d offset = basis[0].multiply(Math.cos(angle) * 0.5).add(basis[1].multiply(Math.sin(angle) * 0.5));
			Vec3d at = from.add(dir.multiply(s)).add(offset);
			particles.addParticle(FreeGlowParticle.of(world, sprites, at, dir.multiply(1.2), 1.0, 10, 0.22f,
					strand == 0 ? CYAN : VIOLET));
		}
		// A bright core thread and the odd spark.
		if (random.nextDouble() < 0.6 * budget) {
			Vec3d at = from.add(dir.multiply(start + random.nextDouble() * (end - start)));
			particles.addParticle(FreeGlowParticle.of(world, sprites, at, dir.multiply(1.6), 1.0, 8, 0.35f, WHITE));
		}
		if (random.nextDouble() < 0.4 * budget) {
			Vec3d at = from.add(dir.multiply(start + random.nextDouble() * (end - start)));
			particles.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
					random.nextGaussian() * 0.6, random.nextGaussian() * 0.6, random.nextGaussian() * 0.6);
		}
	}

	/** A funnel of spiralling light between the mouth and the sky, for a wormhole that leaves the dimension. */
	private static void tunnel(ParticleManager particles, ClientWorld world, Sprite[] sprites, Random random, double budget,
			Vec3d mouth, boolean white, long time) {
		double flow = white ? 0.9 : -0.9;
		int count = count(12.0 * budget, random);
		for (int i = 0; i < count; i++) {
			double h = 2.0 + random.nextDouble() * (TUNNEL_HEIGHT - 2.0);
			double radius = 0.6 + 0.045 * h;
			double angle = h * 0.3 - flow * time * 0.2 + (i & 1) * Math.PI;
			Vec3d at = mouth.add(Math.cos(angle) * radius, h, Math.sin(angle) * radius);
			float[] color = (i & 1) == 0 ? CYAN : white ? WHITE : VIOLET;
			particles.addParticle(FreeGlowParticle.of(world, sprites, at, new Vec3d(0.0, flow, 0.0), 1.0, 12, 0.25f, color));
		}
		// The swirling rim of the tunnel's far end.
		double rim = 0.6 + 0.045 * TUNNEL_HEIGHT;
		for (int j = 0; j < 4; j++) {
			double theta = time * 0.15 + j * Math.PI / 2.0;
			Vec3d at = mouth.add(Math.cos(theta) * rim * 1.3, TUNNEL_HEIGHT, Math.sin(theta) * rim * 1.3);
			particles.addParticle(FreeGlowParticle.of(world, sprites, at, Vec3d.ZERO, 1.0, 8, 0.4f, white ? WHITE : VIOLET));
		}
	}

	/** Two unit vectors perpendicular to {@code dir} and to each other. */
	private static Vec3d[] perpendicularBasis(Vec3d dir) {
		Vec3d helper = Math.abs(dir.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
		Vec3d u = dir.crossProduct(helper).normalize();
		return new Vec3d[] {u, dir.crossProduct(u)};
	}

	static Vec3d randomDirection(Random random) {
		double y = random.nextDouble() * 2.0 - 1.0;
		double phi = random.nextDouble() * 2.0 * Math.PI;
		double rho = Math.sqrt(1.0 - y * y);
		return new Vec3d(rho * Math.cos(phi), y, rho * Math.sin(phi));
	}

	/** Integer part of {@code rate}, plus one with the probability of its fractional part. */
	static int count(double rate, Random random) {
		int whole = (int) rate;
		return whole + (random.nextDouble() < rate - whole ? 1 : 0);
	}
}
