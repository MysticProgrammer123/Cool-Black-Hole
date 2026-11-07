package com.prof.blackhole.client;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.GravityField;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * The pull on the client's own player. Player movement is client-authoritative, so the client applies the holes'
 * gravity ({@link GravityField}, the same code the server uses for every other entity) to its player itself, every
 * tick, without waiting for velocity packets; the server leaves the velocity of players with this mod alone
 * ({@code GravityHandler#pullsOnClient}). The result is a smooth, lag-free pull in multiplayer, where a velocity
 * forced from the server would fight the player's own input and stutter with latency.
 */
public final class ClientGravity {
	private ClientGravity() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ClientGravity::tick);
	}

	private static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.isPaused() || ClientBlackHoleState.isEmpty()) {
			return;
		}
		if (player.isSpectator() || player.hasVehicle() || player.getAbilities().flying) {
			return;
		}
		Vec3d center = player.getBoundingBox().getCenter();
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			// Inside a white hole's core the server throws the player out; nothing to add here.
			if (hole.white() && !hole.frozen() && center.distanceTo(hole.pos()) < hole.horizonRadius()) {
				return;
			}
		}
		GravityField.Pull pull = GravityField.apply(ClientBlackHoleState.all(), center, player.getVelocity(), 1.0, false);
		if (pull.acceleration() > 0.0) {
			player.setVelocity(pull.velocity());
		}
	}
}
