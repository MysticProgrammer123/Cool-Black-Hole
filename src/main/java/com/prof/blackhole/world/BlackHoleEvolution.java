package com.prof.blackhole.world;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.HoleRef;
import com.prof.blackhole.item.BlackHoleItems;
import com.prof.blackhole.sound.BlackHoleSounds;
import com.prof.blackhole.wormhole.WhiteHoleManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Mass bookkeeping of every hole, black and white.
 * <ul>
 *     <li><b>Black holes</b> grow from what they swallow (r_s grows with them); one that has swallowed nothing for a
 *     while is quiescent, glows with Hawking radiation and slowly evaporates, dM/dt = -K / M^2. Matter sent through
 *     a wormhole still adds its mass to the black hole it fell into.</li>
 *     <li><b>White holes</b> run that history backwards: what they eject is mass they lose (so a wormhole carries
 *     mass from its white mouth to its black one, as for real traversable wormholes), and while they eject nothing
 *     they slowly gather mass from the ambient radiation, dM/dt = +K / M^2, the time reverse of evaporation.</li>
 * </ul>
 * A black hole that evaporates, or a white hole that runs out of mass, ends in a final burst and leaves a
 * Singularity Core floating where it was. Two holes of the same kind whose horizons touch merge into one, which
 * keeps 95% of their combined mass (the rest leaves as gravitational waves). Frozen holes do not evolve, stable
 * ones keep their mass.
 * <p>
 * Swallowed mass is collected per hole and applied once a second, so a hole eating a forest sends one sync per
 * second rather than one per block. Also plays every black hole's gravitational siren.
 */
public final class BlackHoleEvolution {
	/** Mass of one swallowed block, M_J (so 5000 blocks double a 1 M_J hole). */
	public static final double BLOCK_MASS = 2.0e-4;
	/** Mass of one fluid source block, M_J. */
	public static final double FLUID_MASS = 1.0e-4;
	/** Mass of one item, M_J. */
	public static final double ITEM_MASS = 2.0e-5;
	/** Mass of an entity per cubic block of its hitbox, M_J (a player ~6.5e-4, the Ender Dragon ~2). */
	public static final double ENTITY_MASS_PER_VOLUME = 1.0e-3;
	/** A single swallow at least this heavy, M_J, sends out a gravitational-wave burst. */
	private static final double LARGE_MASS = 0.01;
	/** Pending mass is applied every this many ticks (each hole at its own phase). */
	private static final int UPDATE_INTERVAL = 20;
	/** Changes smaller than this fraction of the mass are carried over instead of being synced. */
	private static final double MIN_RELATIVE_CHANGE = 1.0e-4;
	/** A hole that has swallowed (a white hole: ejected) nothing for this many ticks is quiescent. */
	private static final long QUIESCENT_TICKS = 200;
	/**
	 * Evaporation constant K in dM/dt = -K / M^2, M_J^3 per second. Hugely exaggerated (a real 1 M_J hole would
	 * outlive the universe by ~10^60), but still slow: a 1 M_J hole lasts M^3 / 3K ~ 4.6 hours of quiescence.
	 */
	private static final double HAWKING_RATE = 2.0e-5;
	/** The siren repeats every this many ticks (the clip is eight seconds long). */
	private static final int SIREN_INTERVAL = 160;
	/** Fraction of the combined mass radiated as gravitational waves when two holes merge. */
	private static final double MERGER_RADIATED = 0.05;
	/** Spin of the remnant of an equal-mass merger of non-spinning holes. */
	private static final double MERGER_REMNANT_SPIN = 0.69;

	private static final Map<HoleRef, Activity> ACTIVITY = new HashMap<>();
	/** Entities whose mass has already been counted (the dragon, for one, keeps living through its death animation). */
	private static final Set<Entity> CONSUMED = Collections.newSetFromMap(new WeakHashMap<>());

	private static final class Activity {
		double pendingMass;
		long lastMeal = Long.MIN_VALUE / 2;
		boolean feeding;
	}

	private BlackHoleEvolution() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(BlackHoleEvolution::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVITY.clear();
			CONSUMED.clear();
		});
	}

	/** Whether the black hole is swallowing matter (the white hole: ejecting it). */
	public static boolean isFeeding(ServerWorld world, BlackHoleData hole) {
		Activity activity = ACTIVITY.get(ref(world, hole));
		return activity != null && activity.feeding;
	}

	/** A block torn loose and swallowed (and, through a wormhole, sent on to its white hole). */
	public static void consumeBlock(ServerWorld world, BlackHoleData hole, BlockState state) {
		WhiteHoleManager.expel(world, hole, state);
		ate(world, hole, BLOCK_MASS);
	}

	public static void consumeFluid(ServerWorld world, BlackHoleData hole, boolean source) {
		ate(world, hole, source ? FLUID_MASS : 0.0);
	}

	public static void consumeItems(ServerWorld world, BlackHoleData hole, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		WhiteHoleManager.expel(world, hole, stack.copy());
		ate(world, hole, ITEM_MASS * stack.getCount());
	}

	/** Counts an entity crossing the horizon; each entity is counted once, however long its death takes. */
	public static void consumeEntity(ServerWorld world, BlackHoleData hole, Entity entity) {
		if (!CONSUMED.add(entity)) {
			return;
		}
		if (entity instanceof ItemEntity item) {
			consumeItems(world, hole, item.getStack());
			return;
		}
		if (entity instanceof FallingBlockEntity block) {
			consumeBlock(world, hole, block.getBlockState());
			return;
		}
		Box box = entity.getBoundingBox();
		double mass = box.getLengthX() * box.getLengthY() * box.getLengthZ() * ENTITY_MASS_PER_VOLUME;
		ate(world, hole, mass);
		if (mass >= LARGE_MASS || entity instanceof EnderDragonEntity || entity instanceof WitherEntity) {
			BlackHoleEffects.shockwave(world, hole.pos(), (float) MathHelper.clamp(0.6 + 0.5 * mass, 0.6, 2.0));
		}
	}

	/** A white hole ejected matter of this mass (M_J): it loses that mass, and is active for a while. */
	public static void ejected(ServerWorld world, BlackHoleData whiteHole, double mass) {
		Activity activity = ACTIVITY.computeIfAbsent(ref(world, whiteHole), key -> new Activity());
		activity.lastMeal = world.getTime();
		activity.pendingMass -= mass;
	}

	private static void ate(ServerWorld world, BlackHoleData hole, double mass) {
		Activity activity = ACTIVITY.computeIfAbsent(ref(world, hole), key -> new Activity());
		activity.lastMeal = world.getTime();
		activity.pendingMass += mass;
	}

	private static HoleRef ref(ServerWorld world, BlackHoleData hole) {
		return new HoleRef(world.getRegistryKey(), hole.id());
	}

	private static void tick(ServerWorld world) {
		long now = world.getTime();
		List<BlackHoleData> holes = List.copyOf(BlackHoleManager.holes(world));
		Set<String> ids = new HashSet<>();
		for (BlackHoleData hole : holes) {
			ids.add(hole.id());
		}
		ACTIVITY.keySet().removeIf(ref -> ref.dimension().equals(world.getRegistryKey()) && !ids.contains(ref.id()));
		if (holes.isEmpty()) {
			return;
		}
		if (holes.size() > 1 && now % UPDATE_INTERVAL == UPDATE_INTERVAL / 2 && merge(world, holes)) {
			return; // the set of holes changed; carry on next tick
		}

		boolean evolution = world.getGameRules().getBoolean(BlackHoleGameRules.MASS_EVOLUTION);
		for (BlackHoleData hole : holes) {
			if (hole.frozen()) {
				continue;
			}
			if (hole.bornTime() < 0L) {
				hole = hole.withBornTime(now);
				BlackHoleManager.put(world, hole);
			}
			Activity activity = ACTIVITY.computeIfAbsent(ref(world, hole), key -> new Activity());
			boolean feeding = now - activity.lastMeal < QUIESCENT_TICKS;
			if (feeding != activity.feeding) {
				activity.feeding = feeding;
				BlackHoleEffects.feeding(world, hole, feeding);
			}

			int phase = Math.floorMod(hole.id().hashCode(), SIREN_INTERVAL);
			if (!hole.white() && (now + phase) % SIREN_INTERVAL == 0L) {
				// Heavier holes wail lower: one octave per ~16x in mass, centred on 1 M_J.
				float pitch = (float) MathHelper.clamp(1.0 - 0.25 * Math.log10(hole.mass()), 0.5, 1.4);
				world.playSound(null, hole.x(), hole.y(), hole.z(), BlackHoleSounds.GRAVITATIONAL_SIREN, SoundCategory.AMBIENT,
					1.0F, pitch);
			}

			if ((now + phase) % UPDATE_INTERVAL != 0L) {
				continue;
			}
			if (!evolution || hole.stable()) {
				activity.pendingMass = 0.0;
				continue;
			}
			double mass = hole.mass();
			if (!feeding) {
				// Hawking evaporation, or for a white hole its time reverse.
				double rate = HAWKING_RATE / (mass * mass) * (UPDATE_INTERVAL / 20.0);
				activity.pendingMass += hole.white() ? rate : -rate;
			}
			double target = mass + activity.pendingMass;
			if (target <= BlackHoleData.MIN_MASS && (hole.white() || !feeding)) {
				end(world, hole);
				continue;
			}
			if (Math.abs(activity.pendingMass) < MIN_RELATIVE_CHANGE * mass) {
				continue;
			}
			activity.pendingMass = 0.0;
			double clamped = MathHelper.clamp(target, BlackHoleData.MIN_MASS, BlackHoleData.MAX_MASS);
			if (clamped != mass) {
				BlackHoleData updated = hole.withMass(clamped);
				BlackHoleManager.put(world, updated);
				if (!hole.white()) {
					for (double critical : HawkingRadiation.CRITICAL_MASSES) {
						if (mass > critical && clamped <= critical) {
							HawkingRadiation.flare(world, updated);
							break;
						}
					}
				}
			}
		}
	}

	/** The last of the mass goes in a final burst: an evaporation flash, or a white hole's detonation. */
	private static void end(ServerWorld world, BlackHoleData hole) {
		BlackHoleManager.remove(world, hole.id());
		ACTIVITY.remove(ref(world, hole));
		if (hole.white()) {
			BlackHoleEffects.detonation(world, hole.pos(), 2.0F);
		} else {
			BlackHoleEffects.evaporated(world, hole.pos());
		}
		dropCore(world, hole.pos());
	}

	/** A Singularity Core, hovering and glowing where the hole was; it never despawns. */
	private static void dropCore(ServerWorld world, Vec3d pos) {
		ItemEntity core = new ItemEntity(world, pos.x, pos.y, pos.z, new ItemStack(BlackHoleItems.SINGULARITY_CORE), 0.0, 0.0, 0.0);
		core.setNoGravity(true);
		core.setNeverDespawn();
		core.setGlowing(true);
		core.setToDefaultPickupDelay();
		world.spawnEntity(core);
	}

	/**
	 * Merges the first pair of same-kind holes whose horizons touch: the heavier keeps its id, moves to the centre
	 * of mass and takes 95% of the combined mass, with a spin between the mass-weighted mean and the 0.69 of an
	 * equal-mass merger. Returns whether a merger happened.
	 */
	private static boolean merge(ServerWorld world, List<BlackHoleData> holes) {
		List<BlackHoleData> candidates = new ArrayList<>();
		for (BlackHoleData hole : holes) {
			if (!hole.frozen()) {
				candidates.add(hole);
			}
		}
		for (int i = 0; i < candidates.size(); i++) {
			for (int j = i + 1; j < candidates.size(); j++) {
				BlackHoleData a = candidates.get(i);
				BlackHoleData b = candidates.get(j);
				if (a.white() != b.white() || a.pos().distanceTo(b.pos()) >= a.horizonRadius() + b.horizonRadius()) {
					continue;
				}
				BlackHoleData heavier = a.mass() >= b.mass() ? a : b;
				BlackHoleData lighter = heavier == a ? b : a;
				double total = a.mass() + b.mass();
				Vec3d center = a.pos().multiply(a.mass() / total).add(b.pos().multiply(b.mass() / total));
				double meanSpin = (a.spin() * a.mass() + b.spin() * b.mass()) / total;
				double ratio = lighter.mass() / heavier.mass();
				double spin = meanSpin + (MERGER_REMNANT_SPIN - meanSpin) * ratio;
				Optional<com.prof.blackhole.data.HoleRef> link = heavier.link().isPresent() ? heavier.link() : lighter.link();
				BlackHoleData merged = heavier.withPos(center).withMass(total * (1.0 - MERGER_RADIATED)).withSpin(spin).withLink(link);
				if (lighter.white()) {
					// Wormholes that led to the lighter white hole now lead to the merged one.
					HoleRef from = ref(world, lighter);
					HoleRef to = ref(world, heavier);
					for (var located : BlackHoleManager.linkedTo(world.getServer(), from)) {
						BlackHoleManager.put(located.world(), located.hole().withLink(Optional.of(to)));
					}
				}
				BlackHoleManager.remove(world, lighter.id());
				BlackHoleManager.put(world, merged);
				ACTIVITY.remove(ref(world, lighter));
				BlackHoleEffects.merger(world, center, (float) MathHelper.clamp(1.5 + 0.2 * total, 1.5, 4.0));
				return true;
			}
		}
		return false;
	}
}
