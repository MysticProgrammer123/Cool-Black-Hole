package com.prof.blackhole.world;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * The flow of time near holes, for blocks:
 * <ul>
 *     <li><b>Stasis</b> inside 2 r_s of a black hole: gravitational time dilation (dtau/dt &lt; 0.71 at the edge,
 *     falling to 0 at r_s) is strong enough that, seen from outside, nothing there happens. Random ticks (crop
 *     growth, leaf decay, ice melting) and fire ticks within that sphere are suppressed by
 *     {@code ServerWorldMixin}.</li>
 *     <li><b>Acceleration</b> inside 2 r_s of a white hole, the mirror image (dtau/dt = sqrt(1 + r_s / r) &gt; 1):
 *     blocks there get {@value #ACCELERATION} times as many random ticks, so crops shoot up, saplings grow and
 *     copper weathers in minutes.</li>
 * </ul>
 * The spheres of each dimension are cached at the start of its tick, so the per-block check is a few
 * multiplications per hole.
 */
public final class TimeStasis {
	/** Radius of the stasis (and acceleration) sphere in Schwarzschild radii. */
	public static final double RADIUS_RS = 2.0;
	/** A frozen fire tries again after this many ticks. */
	public static final int FIRE_RETRY_TICKS = 20;
	/** Random-tick rate inside a white hole's sphere, relative to normal. */
	private static final double ACCELERATION = 12.0;
	/** Vanilla random ticks per block per tick at the default randomTickSpeed (3 per 16^3 section). */
	private static final double VANILLA_TICKS_PER_BLOCK = 3.0 / 4096.0;
	private static final int MAX_EXTRA_TICKS_PER_HOLE = 64;

	/** x, y, z, radius^2 of each stasis sphere of the dimension. */
	private static final Map<ServerWorld, List<double[]>> SPHERES = new WeakHashMap<>();

	private TimeStasis() {
	}

	public static void register() {
		ServerTickEvents.START_WORLD_TICK.register(world -> {
			List<double[]> spheres = new ArrayList<>();
			for (BlackHoleData hole : BlackHoleManager.holes(world)) {
				if (!hole.white() && !hole.frozen()) {
					double radius = RADIUS_RS * hole.schwarzschildRadius();
					spheres.add(new double[] {hole.x(), hole.y(), hole.z(), radius * radius});
				}
			}
			if (spheres.isEmpty()) {
				SPHERES.remove(world);
			} else {
				SPHERES.put(world, spheres);
			}
		});
		ServerTickEvents.END_WORLD_TICK.register(TimeStasis::accelerate);
	}

	public static boolean isFrozen(ServerWorld world, BlockPos pos) {
		List<double[]> spheres = SPHERES.get(world);
		if (spheres == null) {
			return false;
		}
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		for (double[] sphere : spheres) {
			double dx = x - sphere[0];
			double dy = y - sphere[1];
			double dz = z - sphere[2];
			if (dx * dx + dy * dy + dz * dz < sphere[3]) {
				return true;
			}
		}
		return false;
	}

	/** Extra random ticks at random positions inside every white hole's sphere. */
	private static void accelerate(ServerWorld world) {
		Random random = world.random;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (BlackHoleData hole : BlackHoleManager.holes(world)) {
			if (!hole.white() || hole.frozen()) {
				continue;
			}
			double radius = RADIUS_RS * hole.schwarzschildRadius();
			double volume = 4.0 / 3.0 * Math.PI * radius * radius * radius;
			double rate = volume * VANILLA_TICKS_PER_BLOCK * (ACCELERATION - 1.0) * TickBudget.scale(world.getServer());
			int ticks = Math.min((int) rate + (random.nextDouble() < rate - (int) rate ? 1 : 0), MAX_EXTRA_TICKS_PER_HOLE);
			for (int i = 0; i < ticks; i++) {
				// Uniform in the ball: radius ~ cbrt(u).
				double y = random.nextDouble() * 2.0 - 1.0;
				double phi = random.nextDouble() * 2.0 * Math.PI;
				double rho = Math.sqrt(1.0 - y * y);
				double r = radius * Math.cbrt(random.nextDouble());
				pos.set(hole.x() + r * rho * Math.cos(phi), hole.y() + r * y, hole.z() + r * rho * Math.sin(phi));
				if (world.isOutOfHeightLimit(pos.getY()) || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
					continue;
				}
				BlockState state = world.getBlockState(pos);
				if (state.hasRandomTicks()) {
					state.randomTick(world, pos.toImmutable(), random);
				}
			}
		}
	}
}
