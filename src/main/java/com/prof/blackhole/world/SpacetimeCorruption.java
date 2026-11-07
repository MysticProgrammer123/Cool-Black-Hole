package com.prof.blackhole.world;

import com.prof.blackhole.block.BlackHoleBlocks;
import com.prof.blackhole.block.GravityStabilizerBlock;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PlantBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * The corruption zone of a long-standing black hole. Once a hole has existed for {@value #ONSET_TICKS} ticks (five
 * minutes), the warped spacetime around it starts killing the soil: grass, dirt, podzol, mycelium, moss and mud
 * ({@code #minecraft:dirt}) turn into grey Event Horizon Waste, and the plants on them wither away. The zone starts
 * {@value #START_RADIUS} blocks across and widens by {@value #GROWTH_PER_MINUTE} blocks a minute, up to
 * {@value #MAX_RADIUS}. The client draws cosmic tendrils reaching from the zone into the hole.
 * <p>
 * Needs both {@code blackHoleDestroysBlocks} and {@code blackHoleCorruption}; Gravity Stabilizers protect their
 * surroundings, and frozen and stable holes corrupt nothing. Positions are sampled at random, a few every half
 * second per hole, in loaded chunks only.
 */
public final class SpacetimeCorruption {
	/** Age (ticks) at which a hole starts corrupting the ground. */
	public static final long ONSET_TICKS = 6000L;
	public static final double START_RADIUS = 8.0;
	public static final double GROWTH_PER_MINUTE = 2.0;
	public static final double MAX_RADIUS = 48.0;
	private static final int INTERVAL = 10;
	private static final int SAMPLES = 8;

	private SpacetimeCorruption() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(SpacetimeCorruption::tick);
	}

	/** Radius of the hole's corruption zone at {@code worldTime}, blocks, or 0 before its onset. */
	public static double radius(BlackHoleData hole, long worldTime) {
		if (hole.white() || hole.frozen() || hole.stable() || hole.bornTime() < 0L) {
			return 0.0;
		}
		long age = hole.age(worldTime);
		if (age < ONSET_TICKS) {
			return 0.0;
		}
		double grown = START_RADIUS + (age - ONSET_TICKS) / 1200.0 * GROWTH_PER_MINUTE;
		return Math.max(Math.min(grown, MAX_RADIUS), hole.horizonRadius() + 2.0);
	}

	private static void tick(ServerWorld world) {
		long time = world.getTime();
		if (time % INTERVAL != 0L
			|| !world.getGameRules().getBoolean(BlackHoleGameRules.DESTROYS_BLOCKS)
			|| !world.getGameRules().getBoolean(BlackHoleGameRules.CORRUPTION)) {
			return;
		}
		Random random = world.random;
		int samples = Math.max((int) (SAMPLES * TickBudget.scale(world.getServer())), 1);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (BlackHoleData hole : BlackHoleManager.holes(world)) {
			double radius = radius(hole, time);
			if (radius <= 0.0) {
				continue;
			}
			List<BlockPos> stabilizers = GravityStabilizerBlock.near(world, hole.pos(), radius);
			for (int i = 0; i < samples; i++) {
				double y = random.nextDouble() * 2.0 - 1.0;
				double phi = random.nextDouble() * 2.0 * Math.PI;
				double rho = Math.sqrt(1.0 - y * y);
				double r = radius * Math.cbrt(random.nextDouble());
				pos.set(hole.x() + r * rho * Math.cos(phi), hole.y() + r * y, hole.z() + r * rho * Math.sin(phi));
				if (world.isOutOfHeightLimit(pos.getY()) || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
					continue;
				}
				BlockState state = world.getBlockState(pos);
				if (!state.isIn(BlockTags.DIRT) || state.isOf(BlackHoleBlocks.EVENT_HORIZON_WASTE)
					|| (!stabilizers.isEmpty() && GravityStabilizerBlock.protects(stabilizers, pos))) {
					continue;
				}
				corrupt(world, pos.toImmutable(), random);
			}
		}
	}

	private static void corrupt(ServerWorld world, BlockPos pos, Random random) {
		BlockPos above = pos.up();
		BlockState plant = world.getBlockState(above);
		if (plant.getBlock() instanceof PlantBlock || plant.isIn(BlockTags.REPLACEABLE) && !plant.isAir() && plant.getFluidState().isEmpty()) {
			// Withers away rather than popping off as an item.
			world.setBlockState(above, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
		}
		world.setBlockState(pos, BlackHoleBlocks.EVENT_HORIZON_WASTE.getDefaultState(), Block.NOTIFY_ALL);
		if (random.nextInt(3) == 0) {
			BlackHoleEffects.particle(world, ParticleTypes.REVERSE_PORTAL, Vec3d.ofCenter(above), 6, 0.3, 0.2, 0.3, 0.02);
		}
	}
}
