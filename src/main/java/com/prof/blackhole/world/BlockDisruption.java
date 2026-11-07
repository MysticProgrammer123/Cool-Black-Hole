package com.prof.blackhole.world;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.block.GravityStabilizerBlock;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.HoleRef;
import com.prof.blackhole.entity.GravityHandler;
import com.prof.blackhole.physics.Relativity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldEvents;

/**
 * Tears blocks loose inside the pull of every black hole that is neither frozen nor stable. A block breaks once
 * the hole's pull at its centre exceeds {@code BREAK_ACCELERATION * (1 + hardness)}: leaves and dirt go first,
 * stone closer in, obsidian only next to the horizon of a light hole; unbreakable blocks (bedrock, end portal
 * frames, the {@link #IMMUNE} tag) never do, and nothing within reach of a Gravity Stabilizer does. Torn blocks
 * become phasing falling-block debris launched on a prograde orbit that decays into the hole; fluids are drained;
 * containers spill their contents into orbit; a nether portal bursts as it collapses.
 * <p>
 * TPS protection: each sphere is swept by a cursor that visits every position once per sweep in a scrambled order
 * (index * prime mod volume). The dimension checks a fixed number of positions and breaks a fixed number of blocks
 * per tick, shared between its holes and scaled down by {@link TickBudget} when the server is behind. Unloaded
 * chunks are skipped, never loaded.
 */
public final class BlockDisruption {
	/** Command tag of debris entities, so they are recognised again after a reload. */
	public static final String DEBRIS_TAG = ProfsBlackHole.MOD_ID + ".debris";
	public static final TagKey<Block> IMMUNE = TagKey.of(RegistryKeys.BLOCK, ProfsBlackHole.id("black_hole_immune"));

	/** Pull per unit of (1 + hardness) needed to tear a block loose, blocks/tick^2. */
	private static final double BREAK_ACCELERATION = 0.05;
	/** Probability per sweep of breaking a block that is just at its threshold (certain at 4x the threshold). */
	private static final double BREAK_CHANCE_AT_THRESHOLD = 0.25;
	private static final int CHECKS_PER_TICK = 4096;
	private static final int BREAKS_PER_TICK = 24;
	/** Each hole gets at least this many checks per tick, however many share the dimension. */
	private static final int MIN_CHECKS_PER_HOLE = 256;
	/** Debris entities alive at once, across all dimensions; beyond this, blocks are destroyed outright. */
	private static final int MAX_DEBRIS = 192;
	/** With more entities than this in the pull, no new debris or spilled items are created. */
	private static final int MAX_ENTITIES_IN_PULL = 512;
	/** Debris starts at this fraction of the circular orbit speed, so its orbit decays into the hole. */
	private static final double ORBIT_FRACTION = 0.6;
	private static final double MAX_LAUNCH_SPEED = 1.2;
	/** Fraction of dislodged blocks that play the vanilla break sound and particles (all of them is a din). */
	private static final double BREAK_EFFECT_CHANCE = 0.3;
	/** Stale stabilizer entries are dropped every this many ticks. */
	private static final int STABILIZER_CHECK_INTERVAL = 100;
	/** Large primes; one that does not divide the sweep volume visits every position exactly once per sweep. */
	private static final long[] STRIDES = {1_000_003L, 1_000_033L, 1_000_037L};

	private static final Map<HoleRef, Sweep> SWEEPS = new HashMap<>();
	private static final Set<Entity> DEBRIS = Collections.newSetFromMap(new WeakHashMap<>());

	private static final class Sweep {
		final BlockPos center;
		final int radius;
		final int side;
		final long volume;
		final long stride;
		long cursor;

		Sweep(BlockPos center, int radius) {
			this.center = center;
			this.radius = radius;
			this.side = 2 * radius + 1;
			this.volume = (long) side * side * side;
			long chosen = STRIDES[0];
			for (long prime : STRIDES) {
				if (volume % prime != 0L) {
					chosen = prime;
					break;
				}
			}
			this.stride = chosen;
		}
	}

	private BlockDisruption() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(BlockDisruption::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SWEEPS.clear();
			DEBRIS.clear();
		});
	}

	public static boolean isDebris(Entity entity) {
		return entity instanceof FallingBlockEntity && (DEBRIS.contains(entity) || entity.getCommandTags().contains(DEBRIS_TAG));
	}

	/** Re-applies the debris state that is not saved with the entity (noClip) after a reload. */
	public static void adoptDebris(Entity entity) {
		entity.noClip = true;
		DEBRIS.add(entity);
	}

	/** Whether the hole tears blocks loose at all: a black hole, neither frozen nor stable. */
	public static boolean devours(BlackHoleData hole) {
		return !hole.white() && !hole.frozen() && !hole.stable();
	}

	private static void tick(ServerWorld world) {
		if (world.getTime() % STABILIZER_CHECK_INTERVAL == 0L) {
			GravityStabilizerBlock.validate(world);
		}
		List<BlackHoleData> eaters = new ArrayList<>();
		if (world.getGameRules().getBoolean(BlackHoleGameRules.DESTROYS_BLOCKS)) {
			for (BlackHoleData hole : BlackHoleManager.holes(world)) {
				if (devours(hole)) {
					eaters.add(hole);
				}
			}
		}
		Set<HoleRef> live = new HashSet<>();
		for (BlackHoleData hole : eaters) {
			live.add(new HoleRef(world.getRegistryKey(), hole.id()));
		}
		SWEEPS.keySet().removeIf(ref -> ref.dimension().equals(world.getRegistryKey()) && !live.contains(ref));
		if (eaters.isEmpty()) {
			return;
		}

		double scale = TickBudget.scale(world.getServer());
		int checks = Math.max((int) (CHECKS_PER_TICK * scale) / eaters.size(), MIN_CHECKS_PER_HOLE);
		int breaks = Math.max((int) (BREAKS_PER_TICK * scale) / eaters.size(), 1);
		DEBRIS.removeIf(Entity::isRemoved);
		// With the pull already crowded, torn blocks and spilled items go straight in instead of becoming entities.
		boolean crowded = GravityHandler.trackedCount(world) > MAX_ENTITIES_IN_PULL;
		int[] debrisRoom = {crowded ? 0 : (int) (MAX_DEBRIS * scale) - DEBRIS.size()};
		for (BlackHoleData hole : eaters) {
			sweep(world, hole, checks, breaks, debrisRoom, crowded);
		}
	}

	private static void sweep(ServerWorld world, BlackHoleData hole, int checks, int breaks, int[] debrisRoom, boolean crowded) {
		Vec3d center = hole.pos();
		double rs = hole.schwarzschildRadius();
		double gm = Relativity.gravitationalAcceleration(rs, 1.0); // GM_game, blocks^3/tick^2
		double horizon = hole.horizonRadius();
		// Even the softest block needs BREAK_ACCELERATION, which bounds the sphere that can break at all.
		double reach = Math.min(hole.influenceRadius(), Math.sqrt(gm / BREAK_ACCELERATION));
		int radius = (int) Math.ceil(Math.max(reach, horizon + 1.0));

		BlockPos centerPos = BlockPos.ofFloored(center);
		HoleRef key = new HoleRef(world.getRegistryKey(), hole.id());
		Sweep sweep = SWEEPS.get(key);
		if (sweep == null || sweep.radius != radius || !sweep.center.equals(centerPos)) {
			sweep = new Sweep(centerPos, radius);
			SWEEPS.put(key, sweep);
		}

		List<BlockPos> stabilizers = GravityStabilizerBlock.near(world, center, radius);
		double radiusSq = (radius + 0.5) * (radius + 0.5);
		Random random = world.random;
		BlockPos.Mutable pos = new BlockPos.Mutable();

		for (int i = 0; i < checks && breaks > 0; i++) {
			long index = sweep.cursor * sweep.stride % sweep.volume;
			sweep.cursor = (sweep.cursor + 1) % sweep.volume;
			int dx = (int) (index % sweep.side) - radius;
			int dy = (int) (index / sweep.side % sweep.side) - radius;
			int dz = (int) (index / ((long) sweep.side * sweep.side)) - radius;
			pos.set(centerPos.getX() + dx, centerPos.getY() + dy, centerPos.getZ() + dz);
			if (world.isOutOfHeightLimit(pos.getY())) {
				continue;
			}
			double ox = pos.getX() + 0.5 - center.x;
			double oy = pos.getY() + 0.5 - center.y;
			double oz = pos.getZ() + 0.5 - center.z;
			double r2 = ox * ox + oy * oy + oz * oz;
			if (r2 > radiusSq || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || (!stabilizers.isEmpty() && GravityStabilizerBlock.protects(stabilizers, pos))) {
				continue;
			}
			double r = Math.sqrt(r2);
			double acceleration = gm / Math.max(r2, 0.25);
			Outcome outcome = disrupt(world, hole, pos, state, r, horizon, acceleration, center, gm, random, debrisRoom[0] > 0, crowded);
			if (outcome != Outcome.NONE) {
				breaks--;
				if (outcome == Outcome.DEBRIS) {
					debrisRoom[0]--;
				}
			}
		}
	}

	private enum Outcome { NONE, DESTROYED, DEBRIS }

	private static Outcome disrupt(ServerWorld world, BlackHoleData hole, BlockPos pos, BlockState state, double r, double horizon,
			double acceleration, Vec3d center, double gm, Random random, boolean debrisAllowed, boolean crowded) {
		if (state.getBlock() instanceof FluidBlock) {
			if (acceleration < BREAK_ACCELERATION) {
				return Outcome.NONE;
			}
			drainFluid(world, hole, pos, state, random);
			return Outcome.DESTROYED;
		}
		if (state.isIn(IMMUNE)) {
			return Outcome.NONE;
		}
		if (state.isOf(Blocks.NETHER_PORTAL)) {
			// Unbreakable to players (hardness -1), but held together by nothing more than its frame.
			if (acceleration < BREAK_ACCELERATION) {
				return Outcome.NONE;
			}
			portalBurst(world, pos);
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			BlackHoleEvolution.consumeBlock(world, hole, state);
			return Outcome.DESTROYED;
		}
		float hardness = state.getHardness(world, pos);
		if (hardness < 0.0F) {
			return Outcome.NONE;
		}
		if (r > horizon + 0.5) {
			double threshold = BREAK_ACCELERATION * (1.0 + hardness);
			if (acceleration < threshold
				|| random.nextDouble() >= BREAK_CHANCE_AT_THRESHOLD * acceleration / threshold) {
				return Outcome.NONE;
			}
		}

		if (state.isOf(Blocks.OBSIDIAN)) {
			for (Direction direction : Direction.values()) {
				if (world.getBlockState(pos.offset(direction)).isOf(Blocks.NETHER_PORTAL)) {
					portalBurst(world, pos.offset(direction));
					break;
				}
			}
		}

		BlockEntity blockEntity = world.getBlockEntity(pos);
		boolean insideHorizon = r <= horizon + 0.5;
		if (blockEntity == null && debrisAllowed && !insideHorizon && state.getRenderType() == BlockRenderType.MODEL) {
			launchDebris(world, pos, state, center, gm, random);
			return Outcome.DEBRIS;
		}
		if (blockEntity instanceof Inventory inventory) {
			spill(world, hole, pos, blockEntity, inventory, center, gm, insideHorizon || crowded);
		}
		// Block entities, non-model blocks (debris would be invisible), and anything once the debris budget is spent.
		world.breakBlock(pos, false);
		BlackHoleEvolution.consumeBlock(world, hole, state);
		return Outcome.DESTROYED;
	}

	private static void launchDebris(ServerWorld world, BlockPos pos, BlockState state, Vec3d center, double gm, Random random) {
		if (random.nextDouble() < BREAK_EFFECT_CHANCE) {
			world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, pos, Block.getRawIdFromState(state));
		}
		FallingBlockEntity debris = FallingBlockEntity.spawnFromBlock(world, pos, state);
		debris.dropItem = false;
		debris.setDestroyedOnLanding();
		debris.setNoGravity(true);
		debris.addCommandTag(DEBRIS_TAG);
		debris.setVelocity(orbitVelocity(debris.getPos(), center, gm, 0.03 * (random.nextDouble() - 0.5)));
		adoptDebris(debris);
		GravityHandler.track(world, debris);
	}

	/**
	 * Containers empty into orbit before the block goes; loot chests generate their loot first. With
	 * {@code swallowNow} (inside the horizon, or with the pull too crowded for more entities) the contents are
	 * swallowed along with the block.
	 */
	private static void spill(ServerWorld world, BlackHoleData hole, BlockPos pos, BlockEntity blockEntity, Inventory inventory,
			Vec3d center, double gm, boolean swallowNow) {
		if (blockEntity instanceof LootableInventory lootable) {
			lootable.generateLoot(null);
		}
		Vec3d origin = Vec3d.ofCenter(pos);
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.removeStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (swallowNow) {
				BlackHoleEvolution.consumeItems(world, hole, stack);
				continue;
			}
			Vec3d velocity = orbitVelocity(origin, center, gm, 0.05 * (world.random.nextDouble() - 0.5));
			ItemEntity item = new ItemEntity(world, origin.x, origin.y, origin.z, stack, velocity.x, velocity.y, velocity.z);
			item.setToDefaultPickupDelay();
			world.spawnEntity(item);
			GravityHandler.track(world, item);
		}
	}

	/** Prograde velocity at {@link #ORBIT_FRACTION} of the circular speed sqrt(GM/r), plus a small vertical kick. */
	private static Vec3d orbitVelocity(Vec3d at, Vec3d center, double gm, double vertical) {
		double rx = at.x - center.x;
		double rz = at.z - center.z;
		double rho = Math.sqrt(rx * rx + rz * rz);
		double r = at.distanceTo(center);
		if (rho < 1.0e-3 || r < 1.0e-3) {
			return new Vec3d(0.0, vertical, 0.0);
		}
		double speed = Math.min(ORBIT_FRACTION * Math.sqrt(gm / r), MAX_LAUNCH_SPEED);
		// Prograde tangent normalize(cross(+Y, r)) = (rz, 0, -rx) / rho, as in GravityField.
		return new Vec3d(rz / rho * speed, vertical, -rx / rho * speed);
	}

	private static void drainFluid(ServerWorld world, BlackHoleData hole, BlockPos pos, BlockState state, Random random) {
		boolean source = state.getFluidState().isStill();
		// NOTIFY_ALL: neighbouring fluid flows into the gap, so lakes keep draining toward the hole.
		world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
		BlackHoleEvolution.consumeFluid(world, hole, source);
		if (random.nextInt(4) == 0) {
			boolean lava = state.getFluidState().isIn(FluidTags.LAVA);
			world.spawnParticles(lava ? ParticleTypes.LAVA : ParticleTypes.SPLASH,
				pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, lava ? 2 : 6, 0.3, 0.2, 0.3, 0.05);
		}
	}

	/** A nether portal tearing open: netherrack shards, lava and fire. */
	private static void portalBurst(ServerWorld world, BlockPos pos) {
		Vec3d at = Vec3d.ofCenter(pos);
		BlackHoleEffects.particle(world, new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.NETHERRACK.getDefaultState()),
			at, 40, 0.6, 0.6, 0.6, 0.15);
		BlackHoleEffects.particle(world, ParticleTypes.LAVA, at, 12, 0.5, 0.5, 0.5, 0.0);
		BlackHoleEffects.particle(world, ParticleTypes.FLAME, at, 24, 0.4, 0.4, 0.4, 0.08);
		BlackHoleEffects.particle(world, ParticleTypes.REVERSE_PORTAL, at, 30, 0.4, 0.6, 0.4, 0.1);
		world.playSound(null, pos, SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.BLOCKS, 0.6F, 0.5F);
	}
}
