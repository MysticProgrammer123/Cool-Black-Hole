package com.prof.blackhole.wormhole;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.entity.BlackHoleDamageTypes;
import com.prof.blackhole.world.BlackHoleEffects;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A player who crosses an event horizon spends {@value #DURATION} ticks floating in the Singularity Dimension
 * ({@code profs_black_hole:singularity}, an empty void under the End sky) before the trip ends: either at the far
 * end of a wormhole, or in destruction.
 * <p>
 * Transits are kept in memory. A player who logs out mid-transit resumes it on rejoining; one found in the
 * Singularity Dimension after a restart, with no transit on record, is returned to their respawn point.
 */
public final class SingularityTransit {
	public static final RegistryKey<World> DIMENSION = RegistryKey.of(RegistryKeys.WORLD, ProfsBlackHole.id("singularity"));

	/** Time spent in the Singularity Dimension, ticks (five seconds). */
	private static final int DURATION = 100;
	private static final double ARRIVAL_Y = 128.0;
	/** Travellers are spread over this many spots so they do not meet each other. */
	private static final int SLOTS = 64;
	private static final double SLOT_SPACING = 4096.0;
	/** Slow Falling after emerging, ticks: an exit can be high above the ground. */
	private static final int EXIT_SLOW_FALLING = 200;

	/** Where a transit ends; {@code null} from {@link #resolve} falls back to the player's respawn point. */
	@FunctionalInterface
	public interface Exit {
		@Nullable
		TeleportTarget resolve(ServerPlayerEntity player);
	}

	private static final class Transit {
		/** {@code null}: the trip ends in destruction. */
		@Nullable
		final Exit exit;
		int ticksLeft;

		Transit(@Nullable Exit exit, int ticksLeft) {
			this.exit = exit;
			this.ticksLeft = ticksLeft;
		}
	}

	private static final Map<UUID, Transit> TRANSITS = new HashMap<>();

	private SingularityTransit() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(SingularityTransit::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> TRANSITS.clear());
	}

	public static boolean isInTransit(ServerPlayerEntity player) {
		return TRANSITS.containsKey(player.getUuid());
	}

	/** Sends the player through the Singularity Dimension; {@code exit == null} means they will not come back. */
	public static void begin(ServerPlayerEntity player, @Nullable Exit exit) {
		if (isInTransit(player)) {
			return;
		}
		ServerWorld singularity = player.getServer().getWorld(DIMENSION);
		if (singularity == null) {
			// The dimension's datapack is disabled: skip straight to the end of the trip.
			finish(player, exit);
			return;
		}
		int slot = Math.floorMod(player.getUuid().hashCode(), SLOTS);
		Vec3d arrival = new Vec3d(slot * SLOT_SPACING + 0.5, ARRIVAL_Y, 0.5);
		player.setNoGravity(true);
		player.fallDistance = 0.0F;
		player.teleportTo(new TeleportTarget(singularity, arrival, Vec3d.ZERO, player.getYaw(), player.getPitch(),
			TeleportTarget.NO_OP));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, DURATION + 60, 0, false, false, false));
		player.playSoundToPlayer(SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.AMBIENT, 0.8F, 0.5F);
		player.playSoundToPlayer(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 1.0F, 0.6F);
		TRANSITS.put(player.getUuid(), new Transit(exit, DURATION));
	}

	/**
	 * Spatial sound of the crossing: resonant tones from points circling the player, rising in pitch as the exit
	 * nears, and the surge of a portal just before ejection. Each traveller has the Singularity Dimension to
	 * themselves (one spot per {@value #SLOT_SPACING} blocks), so positioned sounds reach only them.
	 */
	private static void soundscape(ServerPlayerEntity player, int ticksLeft) {
		int elapsed = DURATION - ticksLeft;
		ServerWorld world = player.getServerWorld();
		if (elapsed > 0 && elapsed % 20 == 0) {
			double angle = elapsed * 0.9;
			Vec3d at = player.getEyePos().add(Math.cos(angle) * 8.0, Math.sin(angle * 0.5) * 3.0, Math.sin(angle) * 8.0);
			float pitch = 0.5F + 1.0F * elapsed / DURATION;
			world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.AMBIENT, 2.0F, pitch);
			world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.AMBIENT, 1.5F, pitch);
		}
		if (ticksLeft == 40) {
			player.playSoundToPlayer(SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.AMBIENT, 0.7F, 1.2F);
		}
	}

	private static void tick(MinecraftServer server) {
		if (TRANSITS.isEmpty()) {
			return;
		}
		for (Iterator<Map.Entry<UUID, Transit>> it = TRANSITS.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
			if (player == null) {
				continue; // offline: resumed on rejoin
			}
			if (!player.isAlive() || !player.getWorld().getRegistryKey().equals(DIMENSION)) {
				// Died or was moved out by other means (a command, for instance).
				it.remove();
				player.setNoGravity(false);
				continue;
			}
			player.setNoGravity(true);
			Transit transit = entry.getValue();
			soundscape(player, transit.ticksLeft);
			if (--transit.ticksLeft <= 0) {
				it.remove();
				finish(player, transit.exit);
			}
		}
	}

	private static void finish(ServerPlayerEntity player, @Nullable Exit exit) {
		player.setNoGravity(false);
		if (exit == null) {
			player.damage(BlackHoleDamageTypes.singularity(player.getServerWorld()), Float.MAX_VALUE);
			return;
		}
		TeleportTarget target = exit.resolve(player);
		if (target == null) {
			target = player.getRespawnTarget(false, TeleportTarget.NO_OP);
		}
		player.teleportTo(target);
		player.setVelocity(target.velocity());
		player.velocityModified = true;
		player.fallDistance = 0.0F;
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, EXIT_SLOW_FALLING, 0, false, false, true));
		BlackHoleEffects.shockwave(target.world(), target.pos(), 0.5F);
		target.world().playSound(null, target.pos().x, target.pos().y, target.pos().z, SoundEvents.ENTITY_ENDERMAN_TELEPORT,
			SoundCategory.PLAYERS, 1.5F, 0.5F);
	}

	private static void onJoin(ServerPlayerEntity player) {
		if (player.getWorld().getRegistryKey().equals(DIMENSION) && !isInTransit(player)) {
			// Finished on the next server tick, once the player is fully in the world.
			TRANSITS.put(player.getUuid(), new Transit(p -> p.getRespawnTarget(false, TeleportTarget.NO_OP), 1));
		}
	}
}
