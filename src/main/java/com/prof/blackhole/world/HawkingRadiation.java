package com.prof.blackhole.world;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.entity.BlackHoleStatusEffects;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * The radiation field of a quiescent (evaporating) hole. Hawking luminosity goes as 1 / M^2, so the radius within
 * which the flux exceeds a fixed dose rate goes as 1 / M: {@value #ZONE_RADIUS_1MJ} blocks for a 1 M_J hole, more
 * for lighter ones, while heavy holes are too cold for their field to reach past the horizon. Survival players
 * inside get Radiation Sickness ({@code profs_black_hole:radiation}), one level stronger within half the radius
 * and two within a quarter, with Nausea from the second level; irradiated players are wrapped in a swirling
 * green-violet aura.
 * <p>
 * As an evaporating hole crosses the critical masses in {@link #CRITICAL_MASSES} it flares: a burst of radiation
 * hits everyone within twice the zone radius.
 */
public final class HawkingRadiation {
	/** Radius of the radiation zone around a 1 M_J hole, blocks. */
	public static final double ZONE_RADIUS_1MJ = 24.0;
	private static final double MAX_ZONE_RADIUS = 64.0;
	/** The zone always reaches at least this far beyond the horizon, blocks. */
	private static final double MIN_HORIZON_MARGIN = 3.0;
	/** Masses (M_J) at which an evaporating hole flares, heaviest first. */
	public static final double[] CRITICAL_MASSES = {0.5, 0.25, 0.1};
	private static final int INTERVAL = 10;
	/** Effect duration, ticks; it outlasts the interval, so it never flickers while the player stays. */
	private static final int DURATION = 60;
	private static final int NAUSEA_DURATION = 120;
	private static final int FLARE_DURATION = 200;
	private static final Vector3f GREEN = new Vector3f(0.45F, 1.0F, 0.35F);
	private static final Vector3f VIOLET = new Vector3f(0.68F, 0.3F, 1.0F);

	private HawkingRadiation() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(HawkingRadiation::tick);
	}

	/** Radius of the hole's radiation zone, blocks (also drawn by the client). */
	public static double zoneRadius(BlackHoleData hole) {
		return Math.max(Math.min(ZONE_RADIUS_1MJ / hole.mass(), MAX_ZONE_RADIUS), hole.horizonRadius() + MIN_HORIZON_MARGIN);
	}

	private static void tick(ServerWorld world) {
		long time = world.getTime();
		if (time % 2L == 0L) {
			for (ServerPlayerEntity player : world.getPlayers()) {
				if (player.hasStatusEffect(BlackHoleStatusEffects.RADIATION)) {
					aura(world, player, time);
				}
			}
		}
		if (time % INTERVAL != 0L) {
			return;
		}
		for (BlackHoleData hole : BlackHoleManager.holes(world)) {
			if (!radiates(hole) || BlackHoleEvolution.isFeeding(world, hole)) {
				continue;
			}
			double radius = zoneRadius(hole);
			for (ServerPlayerEntity player : world.getPlayers()) {
				double r = player.getBoundingBox().getCenter().distanceTo(hole.pos());
				if (r < radius) {
					irradiate(player, r < 0.25 * radius ? 2 : r < 0.5 * radius ? 1 : 0, DURATION);
				}
			}
		}
	}

	/**
	 * Whether the hole has a radiation field when quiescent: a black hole that is neither frozen nor stable (a
	 * stable hole does not evaporate, so it does not radiate either).
	 */
	public static boolean radiates(BlackHoleData hole) {
		return !hole.white() && !hole.frozen() && !hole.stable();
	}

	/** A critical evaporation threshold was crossed: a flare, and a dose for everyone near. */
	public static void flare(ServerWorld world, BlackHoleData hole) {
		double reach = 2.0 * zoneRadius(hole);
		BlackHoleEffects.hawkingFlare(world, hole.pos(), (float) (1.0 / Math.max(hole.mass(), 0.1)));
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(hole.pos()) < reach * reach) {
				irradiate(player, 2, FLARE_DURATION);
			}
		}
	}

	private static void irradiate(ServerPlayerEntity player, int amplifier, int duration) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		// Potion particles off: the aura below replaces them.
		player.addStatusEffect(new StatusEffectInstance(BlackHoleStatusEffects.RADIATION, duration, amplifier, false, false, true));
		if (amplifier >= 1) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, NAUSEA_DURATION, 0, false, false, true));
		}
	}

	/** Two strands of green-violet sparks spiralling up around the player. */
	private static void aura(ServerWorld world, ServerPlayerEntity player, long time) {
		double height = player.getHeight();
		double radius = 0.45 + 0.5 * player.getWidth();
		for (int strand = 0; strand < 2; strand++) {
			double angle = time * 0.35 + strand * Math.PI;
			double y = ((time * 0.04 + strand * 0.5) % 1.0) * height;
			Vec3d at = player.getPos().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
			var dust = new DustColorTransitionParticleEffect(strand == 0 ? GREEN : VIOLET, strand == 0 ? VIOLET : GREEN, 0.8F);
			BlackHoleEffects.particle(world, dust, at, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}
