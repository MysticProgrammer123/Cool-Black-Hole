package com.prof.blackhole.wormhole;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.HoleRef;
import com.prof.blackhole.entity.GravityHandler;
import com.prof.blackhole.world.BlackHoleEvolution;
import com.prof.blackhole.world.ServerFallbackVisuals;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * White holes as wormhole exits, and their ejection pulses. Blocks and items a linked black hole swallows are
 * queued for its white hole and expelled a few per tick (as falling blocks that land and settle, and as items),
 * each costing the white hole the ejected mass ({@link BlackHoleEvolution#ejected}). Every
 * {@value #REPULSION_PULSE_INTERVAL} ticks (each white hole at its own phase, {@link #isPulseTick}) a repulsion
 * shockwave throws everything nearby clear, on top of the steady GM/r^2 push of {@link GravityHandler}; the client
 * draws the pulse at the same world time.
 * <p>
 * The queues live in memory: matter still in transit when the server stops is lost, like anything else that fell
 * into a black hole.
 */
public final class WhiteHoleManager {
	/** Blocks, and merged item stacks, waiting in a wormhole beyond these counts are lost. */
	private static final int MAX_QUEUED_BLOCKS = 1024;
	private static final int MAX_QUEUED_STACKS = 256;
	/**
	 * TPS protection: expulsion pauses while this many expelled entities are still around the white hole (falling
	 * blocks in flight, items not yet picked up or despawned), so a long meal cannot pile up thousands of entities.
	 */
	private static final int MAX_FALLING_NEAR = 24;
	private static final int MAX_ITEMS_NEAR = 64;
	/** Expelled entities are counted within this distance of the white hole, blocks. */
	private static final double CROWD_RADIUS = 24.0;
	private static final double EXPEL_SPEED = 0.45;
	/** Ticks between repulsion shockwaves; the client times its visual pulse to the same world time. */
	public static final long REPULSION_PULSE_INTERVAL = 80L;
	/** The shockwave reaches this many r_s, but at least {@value #MIN_PULSE_RADIUS} blocks. */
	private static final double PULSE_RADIUS_RS = 4.0;
	private static final double MIN_PULSE_RADIUS = 10.0;
	private static final double PULSE_IMPULSE = 0.8;
	private static final int AMBIENT_SOUND_INTERVAL = 120;

	private static final Map<HoleRef, Queue> QUEUES = new HashMap<>();

	/** Matter in transit through one wormhole. Items are merged into full stacks, so they expel as few entities as possible. */
	private static final class Queue {
		final ArrayDeque<BlockState> blocks = new ArrayDeque<>();
		final ArrayDeque<ItemStack> stacks = new ArrayDeque<>();

		void addBlock(BlockState state) {
			if (blocks.size() >= MAX_QUEUED_BLOCKS) {
				blocks.pollFirst();
			}
			blocks.addLast(state);
		}

		void addItems(ItemStack stack) {
			ItemStack last = stacks.peekLast();
			if (last != null && ItemStack.areItemsAndComponentsEqual(last, stack) && last.getCount() < last.getMaxCount()) {
				int moved = Math.min(stack.getCount(), last.getMaxCount() - last.getCount());
				last.increment(moved);
				stack.decrement(moved);
			}
			if (stack.isEmpty()) {
				return;
			}
			if (stacks.size() >= MAX_QUEUED_STACKS) {
				stacks.pollFirst();
			}
			stacks.addLast(stack);
		}
	}

	private WhiteHoleManager() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(WhiteHoleManager::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> QUEUES.clear());
	}

	/** Whether the white hole fires its repulsion shockwave this tick; shared with the client's visual pulse. */
	public static boolean isPulseTick(long worldTime, String id) {
		return (worldTime + Math.floorMod(id.hashCode(), (int) REPULSION_PULSE_INTERVAL)) % REPULSION_PULSE_INTERVAL == 0L;
	}

	/**
	 * Queues swallowed matter ({@link BlockState} or {@link ItemStack}) for the white hole that {@code blackHole}'s
	 * wormhole leads to. Returns false, queuing nothing, when it has none (or that white hole is frozen).
	 */
	public static boolean expel(ServerWorld source, BlackHoleData blackHole, Object matter) {
		if (blackHole.link().isEmpty()) {
			return false;
		}
		var target = BlackHoleManager.whiteHoleOf(source.getServer(), blackHole);
		if (target.isEmpty() || target.get().hole().frozen()) {
			return false;
		}
		Queue queue = QUEUES.computeIfAbsent(target.get().ref(), key -> new Queue());
		if (matter instanceof BlockState state) {
			queue.addBlock(state);
		} else if (matter instanceof ItemStack stack) {
			queue.addItems(stack.copy());
		}
		return true;
	}

	private static void tick(MinecraftServer server) {
		Set<HoleRef> live = new HashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			for (BlackHoleData hole : BlackHoleManager.holes(world, true)) {
				HoleRef ref = new HoleRef(world.getRegistryKey(), hole.id());
				live.add(ref);
				if (!hole.frozen()) {
					tick(world, hole, QUEUES.get(ref));
				}
			}
		}
		QUEUES.keySet().retainAll(live);
	}

	private static void tick(ServerWorld world, BlackHoleData hole, Queue queue) {
		Vec3d pos = hole.pos();
		BlockPos blockPos = BlockPos.ofFloored(pos);
		// Entities spawned into a loaded but non-ticking chunk would pile up without ever moving or merging.
		if (!world.shouldTickEntity(blockPos)) {
			return;
		}
		long time = world.getTime();
		if ((time + Math.floorMod(hole.id().hashCode(), AMBIENT_SOUND_INTERVAL)) % AMBIENT_SOUND_INTERVAL == 0L) {
			world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_CONDUIT_AMBIENT, SoundCategory.AMBIENT, 1.5F, 1.4F);
		}
		if (isPulseTick(time, hole.id())) {
			world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.AMBIENT, 1.5F, 1.8F);
			world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.AMBIENT, 2.0F, 0.6F);
			pulse(world, hole);
			ServerFallbackVisuals.burst(world, pos, 0.6F);
		}
		if (queue != null && (!queue.blocks.isEmpty() || !queue.stacks.isEmpty())) {
			emit(world, hole, pos, blockPos, queue);
		}
	}

	/** At most one block and one item stack per tick, and none while the white hole's surroundings are crowded. */
	private static void emit(ServerWorld world, BlackHoleData hole, Vec3d pos, BlockPos blockPos, Queue queue) {
		Box crowd = Box.of(pos, CROWD_RADIUS * 2.0, CROWD_RADIUS * 2.0, CROWD_RADIUS * 2.0);
		if (!queue.blocks.isEmpty()
			&& world.getEntitiesByClass(FallingBlockEntity.class, crowd, e -> true).size() < MAX_FALLING_NEAR) {
			BlockState state = queue.blocks.pollFirst();
			if (state.contains(Properties.WATERLOGGED)) {
				// spawnFromBlock would leave the block's water behind at the white hole.
				state = state.with(Properties.WATERLOGGED, false);
			}
			if (world.getBlockState(blockPos).isAir()) {
				// The white hole sits in empty space, so spawnFromBlock's reset of this position to air is a no-op.
				FallingBlockEntity block = FallingBlockEntity.spawnFromBlock(world, blockPos, state);
				block.setVelocity(expulsionVelocity(world.random));
				block.addCommandTag(GravityHandler.EJECTA_TAG);
				BlackHoleEvolution.ejected(world, hole, BlackHoleEvolution.BLOCK_MASS);
			} else {
				// Expelled blocks have piled up into the white hole: what comes out now comes out as items.
				ItemStack stack = new ItemStack(state.getBlock().asItem());
				if (!stack.isOf(Items.AIR)) {
					queue.addItems(stack);
				}
			}
		}
		if (!queue.stacks.isEmpty() && world.getEntitiesByClass(ItemEntity.class, crowd, e -> true).size() < MAX_ITEMS_NEAR) {
			Vec3d v = expulsionVelocity(world.random);
			ItemStack stack = queue.stacks.pollFirst();
			BlackHoleEvolution.ejected(world, hole, BlackHoleEvolution.ITEM_MASS * stack.getCount());
			ItemEntity item = new ItemEntity(world, pos.x, pos.y, pos.z, stack, v.x, v.y, v.z);
			item.setToDefaultPickupDelay();
			item.addCommandTag(GravityHandler.EJECTA_TAG);
			world.spawnEntity(item);
		}
	}

	private static Vec3d expulsionVelocity(Random random) {
		return randomDirection(random).multiply(EXPEL_SPEED * (0.6 + 0.8 * random.nextDouble()));
	}

	/** The repulsion shockwave: everything within reach is thrown outward, hardest close in. */
	private static void pulse(ServerWorld world, BlackHoleData hole) {
		Vec3d pos = hole.pos();
		double radius = Math.max(PULSE_RADIUS_RS * hole.schwarzschildRadius(), MIN_PULSE_RADIUS);
		Box box = Box.of(pos, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Entity entity : world.getOtherEntities(null, box, e -> e.isAlive() && !e.isSpectator() && !e.hasVehicle())) {
			Vec3d away = entity.getBoundingBox().getCenter().subtract(pos);
			double r = away.length();
			if (r > radius) {
				continue;
			}
			Vec3d dir = r > 1.0e-3 ? away.multiply(1.0 / r) : new Vec3d(0.0, 1.0, 0.0);
			entity.addVelocity(dir.multiply(PULSE_IMPULSE * (1.0 - r / radius)));
			entity.fallDistance = 0.0F;
			if (entity instanceof ServerPlayerEntity) {
				entity.velocityModified = true;
			}
		}
	}

	private static Vec3d randomDirection(Random random) {
		double y = random.nextDouble() * 2.0 - 1.0;
		double phi = random.nextDouble() * 2.0 * Math.PI;
		double rho = Math.sqrt(1.0 - y * y);
		return new Vec3d(rho * Math.cos(phi), y, rho * Math.sin(phi));
	}
}
