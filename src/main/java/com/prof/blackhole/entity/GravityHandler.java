package com.prof.blackhole.entity;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.network.BlackHoleSyncPayload;
import com.prof.blackhole.physics.GravityField;
import com.prof.blackhole.physics.Relativity;
import com.prof.blackhole.world.BlackHoleEffects;
import com.prof.blackhole.world.BlackHoleEvolution;
import com.prof.blackhole.world.BlockDisruption;
import com.prof.blackhole.world.TickBudget;
import com.prof.blackhole.wormhole.Wormholes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Server-side gravity of every hole in a dimension at once ({@link GravityField}): attraction toward black holes,
 * repulsion from white holes, frame dragging, tidal (spaghettification) damage, gravitational time dilation near
 * black holes and its mirror image, time acceleration, near white holes; black-hole horizon capture, and the
 * expulsion of anything that reaches a white hole's core.
 * <p>
 * Inside a black hole's plunging region (the ISCO, where no stable orbit exists) non-player entities stop colliding
 * with blocks, so terrain, trees or a hole buried in the ground cannot hold back what is already doomed; further
 * out, grounded entities pressed against a ledge are lifted over it instead of grinding against the block. Block
 * debris torn loose by {@link BlockDisruption} phases through terrain all the way in.
 * <p>
 * <b>Multiplayer:</b> player movement is client-authoritative, so a velocity forced from the server fights the
 * player's own input and stutters with latency. Players whose client runs this mod pull themselves (the client
 * applies the same {@link GravityField} to its own player every tick); the server pushes the velocity only to
 * players without it, and keeps doing everything that depends on position (capture, tides, time effects) for all.
 * <p>
 * TPS protection: each hole's influence sphere (up to 384 blocks across) is searched for entities only every
 * {@value #SCAN_INTERVAL} ticks ({@value #SLOW_SCAN_INTERVAL} when the server is behind); in between, the entities
 * found are pulled from a cache, which entities spawned by the holes themselves are added to directly.
 */
public final class GravityHandler {
	/** Command tag of matter a white hole has ejected: it leaves on its own momentum, not pushed again. */
	public static final String EJECTA_TAG = ProfsBlackHole.MOD_ID + ".ejecta";
	/** Pull strength above which accumulated fall distance is discarded, blocks/tick^2. */
	private static final double FALL_RESET_ACCELERATION = 0.01;
	/** Tidal stretch factor at which spaghettification starts hurting. */
	private static final double DAMAGE_STRETCH_THRESHOLD = 3.0;
	/** Pull strength above which grounded non-player entities are helped over ledges, blocks/tick^2. */
	private static final double STEP_ASSIST_ACCELERATION = 0.04;
	/** Upward velocity given to an entity stuck against a ledge, blocks/tick (vanilla jump: 0.42, clears one block). */
	private static final double STEP_ASSIST_VELOCITY = 0.42;
	/** The plunging region extends at least this far beyond the horizon, blocks, so light holes can still be reached. */
	private static final double MIN_PLUNGE_MARGIN = 1.0;
	/** Players closer than this many Schwarzschild radii experience time dilation (or, near a white hole, acceleration). */
	private static final double TIME_EFFECT_RADIUS_RS = 3.0;
	/** Below this dilation factor dtau/dt (r below ~1.56 r_s) the effects step up one level. */
	private static final double STRONG_TIME_DILATION = 0.6;
	/** Above this acceleration factor dtau/dt = sqrt(1 + r_s / r) (the same r, ~1.56 r_s) likewise. */
	private static final double STRONG_TIME_ACCELERATION = 1.28;
	/** Time effects are refreshed every this many ticks and outlast the interval, so they never flicker. */
	private static final int TIME_EFFECT_INTERVAL = 10;
	private static final int TIME_EFFECT_DURATION = 30;
	/** Speed with which anything that reaches a white hole's core is thrown back out, blocks/tick. */
	private static final double CORE_EJECTION_SPEED = 1.2;
	/** Ticks between entity searches of the influence spheres, normally and when the server is behind. */
	private static final int SCAN_INTERVAL = 5;
	private static final int SLOW_SCAN_INTERVAL = 10;
	/** Pull multipliers for End mobs: ender matter couples to curved spacetime more strongly than ordinary matter. */
	private static final double ENDERMAN_COUPLING = 1.5;
	private static final double DRAGON_COUPLING = 2.5;
	/** End-mob particle streams are drawn within this distance of the hole, blocks. */
	private static final double ENDER_STREAM_RANGE = 48.0;

	/**
	 * Entities whose {@code noClip} this handler switched on, so it can switch exactly those back off.
	 * Weak keys: removed or unloaded entities drop out on their own. Only touched on the server thread.
	 */
	private static final Set<Entity> PHASING = Collections.newSetFromMap(new WeakHashMap<>());
	/** Entities found by the last search of each dimension's influence spheres. */
	private static final Map<ServerWorld, List<Entity>> CACHE = new WeakHashMap<>();

	private GravityHandler() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(GravityHandler::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CACHE.clear());
	}

	/** Adds an entity a hole just created (debris, spilled items) to the pulled set without waiting for a search. */
	public static void track(ServerWorld world, Entity entity) {
		List<Entity> cached = CACHE.get(world);
		if (cached != null) {
			cached.add(entity);
		}
	}

	/** Number of entities found inside the dimension's influence spheres by the last search (0 without holes). */
	public static int trackedCount(ServerWorld world) {
		List<Entity> cached = CACHE.get(world);
		return cached == null ? 0 : cached.size();
	}

	/** Whether the entity is inside the influence sphere of an active black hole. */
	public static boolean isInInfluence(ServerWorld world, Entity entity) {
		Vec3d center = entity.getBoundingBox().getCenter();
		for (BlackHoleData hole : BlackHoleManager.holes(world)) {
			if (!hole.white() && !hole.frozen()) {
				double influence = hole.influenceRadius();
				if (center.squaredDistanceTo(hole.pos()) < influence * influence) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether the player's client applies the pull itself: it runs this mod (it can receive the hole sync), so it
	 * knows the holes and moves its own player through {@link GravityField}.
	 */
	public static boolean pullsOnClient(ServerPlayerEntity player) {
		return ServerPlayNetworking.canSend(player, BlackHoleSyncPayload.ID);
	}

	private static void tick(ServerWorld world) {
		List<BlackHoleData> active = new ArrayList<>();
		for (BlackHoleData hole : BlackHoleManager.holes(world)) {
			if (!hole.frozen()) {
				active.add(hole);
			}
		}
		if (active.isEmpty()) {
			CACHE.remove(world);
			releasePhasing(world, Set.of());
			return;
		}
		long time = world.getTime();
		boolean damageTick = time % 20L == 0L;
		boolean timeEffectTick = time % TIME_EFFECT_INTERVAL == 0L;
		Set<Entity> phasing = new HashSet<>();

		int interval = TickBudget.scale(world.getServer()) < 0.5 ? SLOW_SCAN_INTERVAL : SCAN_INTERVAL;
		List<Entity> entities = CACHE.get(world);
		if (entities == null || time % interval == 0L) {
			Set<Entity> found = new LinkedHashSet<>();
			for (BlackHoleData hole : active) {
				double influence = hole.influenceRadius();
				Box box = Box.of(hole.pos(), influence * 2.0, influence * 2.0, influence * 2.0);
				found.addAll(world.getOtherEntities(null, box, GravityHandler::isAffected));
			}
			// A fresh, mutable list: entities swallowed below may be removed while it is iterated by index.
			entities = new ArrayList<>(found);
			CACHE.put(world, entities);
		}

		for (int i = 0; i < entities.size(); i++) {
			Entity entity = entities.get(i);
			if (!isAffected(entity) || entity.getWorld() != world) {
				continue;
			}
			Vec3d bodyCenter = entity.getBoundingBox().getCenter();
			boolean exempt = isDamageExempt(entity);

			// The hole pulling hardest decides the local effects; a black-hole horizon captures at once.
			BlackHoleData dominant = null;
			double dominantAcceleration = 0.0;
			double dominantR = 0.0;
			boolean swallowed = false;
			for (BlackHoleData hole : active) {
				double r = bodyCenter.distanceTo(hole.pos());
				if (r > hole.influenceRadius()) {
					continue;
				}
				if (!hole.white() && !exempt && (r < hole.horizonRadius() || touchesSingularity(entity, hole.pos()))) {
					swallow(world, hole, entity);
					swallowed = true;
					break;
				}
				double acceleration = Relativity.gravitationalAcceleration(hole.schwarzschildRadius(), Math.max(r, 1.0e-3));
				if (dominant == null || acceleration > dominantAcceleration) {
					dominant = hole;
					dominantAcceleration = acceleration;
					dominantR = r;
				}
			}
			if (swallowed || dominant == null) {
				continue;
			}
			double rs = dominant.schwarzschildRadius();

			if (dominant.white() && dominantR < dominant.horizonRadius()) {
				ejectFromCore(entity, dominant, bodyCenter, dominantR);
			} else if (!(entity instanceof PlayerEntity player && player.getAbilities().flying)) {
				pull(entity, active, bodyCenter, dominantAcceleration);
			}

			if (BlockDisruption.isDebris(entity)) {
				// Torn-loose blocks phase through terrain all the way in (noClip is not saved, so re-assert it).
				BlockDisruption.adoptDebris(entity);
			} else if (!exempt && !(entity instanceof PlayerEntity)) {
				// Player movement is client-authoritative; collisions are left to the player's client.
				double plunge = Math.max(Relativity.iscoRadius(rs, dominant.spin()), dominant.horizonRadius() + MIN_PLUNGE_MARGIN);
				if (!dominant.white() && dominantR < plunge) {
					phase(entity, phasing);
				} else {
					assistStep(entity, dominantAcceleration);
				}
			}

			if (damageTick && !exempt && entity instanceof LivingEntity living) {
				// Tides stretch along the line to a black hole and crush along the line to a white hole; both hurt.
				double stretch = Relativity.tidalStretch(rs, dominantR, entity.getHeight());
				if (stretch > DAMAGE_STRETCH_THRESHOLD) {
					// One heart per second at the threshold, up to four hearts at the maximum stretch.
					float damage = (float) (2.0 * (stretch - DAMAGE_STRETCH_THRESHOLD + 1.0));
					living.damage(BlackHoleDamageTypes.singularity(world), damage);
				}
			}

			if (timeEffectTick && dominantR < TIME_EFFECT_RADIUS_RS * rs) {
				if (dominant.white() && entity instanceof LivingEntity living) {
					applyTimeAcceleration(living, rs, dominantR);
				} else if (!dominant.white() && !exempt && entity instanceof ServerPlayerEntity player) {
					applyTimeDilation(player, rs, dominantR);
				}
			}

			if (!dominant.white() && dominantR < ENDER_STREAM_RANGE) {
				enderStreams(world, dominant.pos(), entity, bodyCenter, time);
			}
		}
		releasePhasing(world, phasing);
	}

	private static boolean isAffected(Entity entity) {
		return entity.isAlive()                  // not removed, and living entities not dying
			&& !entity.isSpectator()
			&& !entity.hasVehicle()               // the vehicle is pulled instead
			&& !(entity instanceof EnderDragonPart); // parts follow their dragon
	}

	private static boolean isDamageExempt(Entity entity) {
		return entity instanceof PlayerEntity player && (player.isCreative() || player.isSpectator());
	}

	private static double coupling(Entity entity) {
		if (entity instanceof EnderDragonEntity) {
			return DRAGON_COUPLING;
		}
		return entity instanceof EndermanEntity ? ENDERMAN_COUPLING : 1.0;
	}

	/**
	 * Whether the singularity lies inside the volume the entity swept this tick. Matters for light holes,
	 * whose horizon is smaller than a body: a fast entity would otherwise step over the horizon sphere.
	 */
	private static boolean touchesSingularity(Entity entity, Vec3d center) {
		Box now = entity.getBoundingBox();
		Box before = now.offset(entity.prevX - entity.getX(), entity.prevY - entity.getY(), entity.prevZ - entity.getZ());
		return now.union(before).contains(center);
	}

	private static void pull(Entity entity, List<BlackHoleData> holes, Vec3d bodyCenter, double dominantAcceleration) {
		if (entity instanceof ServerPlayerEntity player && pullsOnClient(player)) {
			// The player's own client applies the pull; only the server-side fall tracking needs resetting.
			if (dominantAcceleration > FALL_RESET_ACCELERATION) {
				entity.fallDistance = 0.0F;
			}
			return;
		}
		boolean ejecta = entity.getCommandTags().contains(EJECTA_TAG);
		GravityField.Pull pull = GravityField.apply(holes, bodyCenter, entity.getVelocity(), coupling(entity), ejecta);
		if (pull.acceleration() <= 0.0) {
			return;
		}
		entity.setVelocity(pull.velocity());
		entity.velocityDirty = true;
		if (entity instanceof ServerPlayerEntity) {
			// A client without the mod: this forces a velocity packet.
			entity.velocityModified = true;
		}
		if (pull.acceleration() > FALL_RESET_ACCELERATION) {
			entity.fallDistance = 0.0F;
		}
	}

	/** Nothing can enter a white hole: whatever reaches its core is flung straight back out. */
	private static void ejectFromCore(Entity entity, BlackHoleData hole, Vec3d bodyCenter, double r) {
		Vec3d away = r > 1.0e-3 ? bodyCenter.subtract(hole.pos()).multiply(1.0 / r) : new Vec3d(0.0, 1.0, 0.0);
		entity.setVelocity(away.multiply(CORE_EJECTION_SPEED));
		entity.velocityDirty = true;
		entity.velocityModified = true;
		entity.fallDistance = 0.0F;
	}

	/** Lifts a grounded entity that the pull is grinding against a ledge (a block edge, a tree trunk) over it. */
	private static void assistStep(Entity entity, double acceleration) {
		if (acceleration < STEP_ASSIST_ACCELERATION || !entity.horizontalCollision || !entity.isOnGround()) {
			return;
		}
		Vec3d velocity = entity.getVelocity();
		if (velocity.y < STEP_ASSIST_VELOCITY) {
			entity.setVelocity(velocity.x, STEP_ASSIST_VELOCITY, velocity.z);
			entity.velocityDirty = true;
		}
	}

	/** Lets an entity inside the plunging region pass through blocks on its way in (noClip also suppresses suffocation). */
	private static void phase(Entity entity, Set<Entity> phasing) {
		if (PHASING.contains(entity)) {
			// Re-assert: some entities (items, for example) recompute noClip in their own tick.
			entity.noClip = true;
			phasing.add(entity);
		} else if (!entity.noClip) {
			// Entities that are noClip on their own (vexes, the dragon) are left alone.
			entity.noClip = true;
			PHASING.add(entity);
			phasing.add(entity);
		}
	}

	/** Restores collisions for the entities of this world that were phasing but are no longer in {@code keep}. */
	private static void releasePhasing(ServerWorld world, Set<Entity> keep) {
		if (PHASING.isEmpty()) {
			return;
		}
		for (Iterator<Entity> it = PHASING.iterator(); it.hasNext(); ) {
			Entity entity = it.next();
			if (entity.isRemoved()) {
				it.remove();
			} else if (entity.getWorld() == world && !keep.contains(entity)) {
				entity.noClip = false;
				it.remove();
			}
		}
	}

	/**
	 * Gravitational time dilation, dtau/dt = sqrt(1 - r_s / r): the player's clock runs slow relative to the
	 * outside world, shown as Slowness and Mining Fatigue (one level stronger deep in the well).
	 */
	private static void applyTimeDilation(ServerPlayerEntity player, double rs, double r) {
		int amplifier = Relativity.timeDilationFactor(rs, r) < STRONG_TIME_DILATION ? 1 : 0;
		// Ambient, no particles: an environmental effect like a beacon's, not a potion.
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, TIME_EFFECT_DURATION, amplifier, true, false, true));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, TIME_EFFECT_DURATION, amplifier, true, false, true));
	}

	/**
	 * The mirror image near a white hole, dtau/dt = sqrt(1 + r_s / r): clocks run fast, shown as Speed and Haste (one
	 * level stronger close to the core). Mobs are sped up too.
	 */
	private static void applyTimeAcceleration(LivingEntity living, double rs, double r) {
		int amplifier = Relativity.timeAccelerationFactor(rs, r) > STRONG_TIME_ACCELERATION ? 1 : 0;
		living.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, TIME_EFFECT_DURATION, amplifier, true, false, true));
		if (living instanceof PlayerEntity) {
			living.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, TIME_EFFECT_DURATION, amplifier, true, false, true));
		}
	}

	/**
	 * End mobs bleed ender matter into the hole: endermen trail streams of portal particles that converge on the
	 * singularity (and shriek, unable to teleport away), the dragon trails dragon's breath.
	 */
	private static void enderStreams(ServerWorld world, Vec3d center, Entity entity, Vec3d bodyCenter, long time) {
		if (entity instanceof EndermanEntity && time % 3L == 0L) {
			// Portal particles travel from position + velocity back to position: here, from the enderman to the hole.
			Vec3d offset = bodyCenter.subtract(center);
			BlackHoleEffects.particle(world, ParticleTypes.PORTAL, center, 0, offset.x, offset.y, offset.z, 1.0);
			if (time % 60L == 0L && world.random.nextInt(3) == 0) {
				world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_ENDERMAN_SCREAM,
					SoundCategory.HOSTILE, 1.0F, 0.6F);
			}
		} else if (entity instanceof EnderDragonEntity) {
			for (int i = 0; i < 4; i++) {
				Vec3d at = bodyCenter.lerp(center, world.random.nextDouble());
				Vec3d inward = center.subtract(at).normalize().multiply(0.4);
				BlackHoleEffects.particle(world, ParticleTypes.DRAGON_BREATH, at, 0, inward.x, inward.y, inward.z, 1.0);
			}
		}
	}

	private static void swallow(ServerWorld world, BlackHoleData hole, Entity entity) {
		if (entity instanceof ServerPlayerEntity player) {
			Wormholes.enterHorizon(world, hole, player);
			return;
		}
		BlackHoleEvolution.consumeEntity(world, hole, entity);
		if (entity instanceof LivingEntity living) {
			living.damage(BlackHoleDamageTypes.singularity(world), Float.MAX_VALUE);
		} else {
			entity.discard();
		}
	}
}
