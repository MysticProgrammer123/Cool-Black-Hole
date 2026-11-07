package com.prof.blackhole.world;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.network.BlackHoleEventPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

/**
 * Server-side triggers for the client-drawn hole events, plus helpers for long-range vanilla particles. Every
 * payload goes only to players whose client can receive it ({@link ServerPlayNetworking#canSend}); players without
 * the mod's client get vanilla particles from {@link ServerFallbackVisuals} instead.
 */
public final class BlackHoleEffects {
	/** Players farther than this from an event are not told about it, blocks. */
	private static final double EVENT_RANGE = 256.0;
	/** Vanilla particles are sent with the long-distance flag to players within this range, blocks. */
	private static final double PARTICLE_RANGE = 128.0;

	private BlackHoleEffects() {
	}

	/** A spherical gravitational-wave burst at {@code pos}; strength ~1 for a newly formed hole. */
	public static void shockwave(ServerWorld world, Vec3d pos, float strength) {
		broadcast(world, pos, new BlackHoleEventPayload(BlackHoleEventPayload.Kind.SHOCKWAVE, pos, strength, ""));
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.AMBIENT,
			4.0F, 0.5F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.AMBIENT,
			3.0F, 0.45F);
		ServerFallbackVisuals.burst(world, pos, strength);
	}

	/** The final burst of a hole that has evaporated completely. */
	public static void evaporated(ServerWorld world, Vec3d pos) {
		broadcast(world, pos, new BlackHoleEventPayload(BlackHoleEventPayload.Kind.EVAPORATED, pos, 1.0F, ""));
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.AMBIENT,
			4.0F, 1.6F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.AMBIENT,
			4.0F, 0.6F);
		ServerFallbackVisuals.burst(world, pos, 1.0F);
	}

	/** A burst of Hawking radiation from an evaporating hole crossing a critical mass. */
	public static void hawkingFlare(ServerWorld world, Vec3d pos, float strength) {
		broadcast(world, pos, new BlackHoleEventPayload(BlackHoleEventPayload.Kind.HAWKING_FLARE, pos, strength, ""));
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.AMBIENT, 4.0F, 1.3F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), SoundCategory.AMBIENT,
			3.0F, 0.6F);
		ServerFallbackVisuals.burst(world, pos, strength);
	}

	/** A white hole that has run out of mass blows apart in one last, blinding ejection. */
	public static void detonation(ServerWorld world, Vec3d pos, float strength) {
		broadcast(world, pos, new BlackHoleEventPayload(BlackHoleEventPayload.Kind.DETONATION, pos, strength, ""));
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.AMBIENT, 5.0F, 1.2F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.AMBIENT, 4.0F, 1.6F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 4.0F, 1.4F);
		ServerFallbackVisuals.burst(world, pos, strength);
	}

	/** Two holes merged into one: a strong gravitational-wave burst as the new horizon rings down. */
	public static void merger(ServerWorld world, Vec3d pos, float strength) {
		broadcast(world, pos, new BlackHoleEventPayload(BlackHoleEventPayload.Kind.MERGER, pos, strength, ""));
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.AMBIENT, 6.0F, 0.35F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 5.0F, 0.5F);
		world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.AMBIENT, 4.0F, 0.4F);
		ServerFallbackVisuals.burst(world, pos, strength);
	}

	/** Tells every player in the dimension whether a hole is accreting (a white hole: ejecting). */
	public static void feeding(ServerWorld world, BlackHoleData hole, boolean feeding) {
		var payload = feedingPayload(hole, feeding);
		for (ServerPlayerEntity player : PlayerLookup.world(world)) {
			send(player, payload);
		}
	}

	public static void sendFeeding(ServerPlayerEntity player, BlackHoleData hole, boolean feeding) {
		send(player, feedingPayload(hole, feeding));
	}

	/**
	 * Spawns a vanilla particle visible from up to {@value #PARTICLE_RANGE} blocks (the default cut-off is 32).
	 * With {@code count = 0} the deltas are the particle's velocity, as for {@code /particle}.
	 */
	public static void particle(ServerWorld world, ParticleEffect effect, Vec3d pos, int count,
			double dx, double dy, double dz, double speed) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(pos) < PARTICLE_RANGE * PARTICLE_RANGE) {
				world.spawnParticles(player, effect, true, pos.x, pos.y, pos.z, count, dx, dy, dz, speed);
			}
		}
	}

	private static BlackHoleEventPayload feedingPayload(BlackHoleData hole, boolean feeding) {
		return new BlackHoleEventPayload(BlackHoleEventPayload.Kind.FEEDING, hole.pos(), feeding ? 1.0F : 0.0F, hole.id());
	}

	private static void broadcast(ServerWorld world, Vec3d pos, BlackHoleEventPayload payload) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(pos) < EVENT_RANGE * EVENT_RANGE) {
				send(player, payload);
			}
		}
	}

	private static void send(ServerPlayerEntity player, BlackHoleEventPayload payload) {
		if (ServerPlayNetworking.canSend(player, BlackHoleEventPayload.ID)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
