package com.prof.blackhole.world;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.entity.GravityHandler;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

/**
 * Pure server-side fallback visuals for players whose client cannot receive the mod's packets (and so has no
 * lensing shader and none of the client-drawn particles): every hole near them is outlined in vanilla particles,
 * sent to those players only. A dark shell marks a black hole's horizon (a bright one a white hole's core), a ring
 * of glowing dust its disk, and bursts flash at shockwaves, flares, detonations and repulsion pulses. Gravity and
 * every other effect reach such players from the server as usual.
 */
public final class ServerFallbackVisuals {
	private static final int INTERVAL = 4;
	/** Holes are drawn for players within this distance of them, blocks. */
	private static final double RANGE = 96.0;
	private static final int SHELL_PARTICLES = 10;
	private static final int DISK_PARTICLES = 14;
	private static final DustParticleEffect BLACK_DISK = new DustParticleEffect(new Vector3f(1.0F, 0.62F, 0.28F), 1.4F);
	private static final DustParticleEffect WHITE_DISK = new DustParticleEffect(new Vector3f(0.62F, 0.95F, 1.0F), 1.4F);

	private ServerFallbackVisuals() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(ServerFallbackVisuals::tick);
	}

	private static List<ServerPlayerEntity> viewers(ServerWorld world) {
		return world.getPlayers(player -> !GravityHandler.pullsOnClient(player));
	}

	private static void tick(ServerWorld world) {
		if (world.getTime() % INTERVAL != 0L) {
			return;
		}
		List<ServerPlayerEntity> viewers = viewers(world);
		if (viewers.isEmpty()) {
			return;
		}
		Random random = world.random;
		for (BlackHoleData hole : BlackHoleManager.holes(world)) {
			double rs = hole.schwarzschildRadius();
			double reach = RANGE + 6.0 * rs;
			for (ServerPlayerEntity player : viewers) {
				if (player.squaredDistanceTo(hole.pos()) > reach * reach) {
					continue;
				}
				double shell = Math.max(hole.horizonRadius() * 1.15, 0.3);
				ParticleEffect shellEffect = hole.white() ? ParticleTypes.END_ROD : ParticleTypes.SQUID_INK;
				for (int i = 0; i < SHELL_PARTICLES; i++) {
					send(world, player, shellEffect, hole.pos().add(direction(random).multiply(shell)));
				}
				if (hole.accretionDisk()) {
					for (int i = 0; i < DISK_PARTICLES; i++) {
						double r = (2.0 + 4.0 * random.nextDouble()) * rs;
						double phi = random.nextDouble() * 2.0 * Math.PI;
						send(world, player, hole.white() ? WHITE_DISK : BLACK_DISK,
							hole.pos().add(Math.sin(phi) * r, 0.0, Math.cos(phi) * r));
					}
				}
			}
		}
	}

	/** A flash and a spray of sparks at {@code pos}, for the players without the mod's client. */
	public static void burst(ServerWorld world, Vec3d pos, float strength) {
		List<ServerPlayerEntity> viewers = viewers(world);
		if (viewers.isEmpty()) {
			return;
		}
		int count = (int) Math.min(20 + 30 * strength, 120);
		for (ServerPlayerEntity player : viewers) {
			if (player.squaredDistanceTo(pos) < 160.0 * 160.0) {
				world.spawnParticles(player, ParticleTypes.FLASH, true, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
				world.spawnParticles(player, ParticleTypes.END_ROD, true, pos.x, pos.y, pos.z, count, 0.2, 0.2, 0.2, 0.4 * strength);
			}
		}
	}

	private static void send(ServerWorld world, ServerPlayerEntity player, ParticleEffect effect, Vec3d at) {
		world.spawnParticles(player, effect, true, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
	}

	private static Vec3d direction(Random random) {
		double y = random.nextDouble() * 2.0 - 1.0;
		double phi = random.nextDouble() * 2.0 * Math.PI;
		double rho = Math.sqrt(1.0 - y * y);
		return new Vec3d(rho * Math.cos(phi), y, rho * Math.sin(phi));
	}
}
