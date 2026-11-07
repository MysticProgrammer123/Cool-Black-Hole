package com.prof.blackhole.wormhole;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.network.WormholeSyncPayload;
import com.prof.blackhole.network.WormholeSyncPayload.Mouth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

/**
 * Keeps every client informed of the wormhole mouths in its dimension: both ends of every black hole's link to its
 * white hole, for the beam (same dimension) or tunnels (across dimensions) the client draws. {@link #refresh} is
 * called whenever a hole changes and only re-sends the dimensions whose mouths actually moved, so the
 * once-a-second mass updates of a growing hole cost nothing here.
 */
public final class WormholeSync {
	/** What each dimension's players were last sent. */
	private static final Map<RegistryKey<World>, List<Mouth>> SENT = new HashMap<>();

	private WormholeSync() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SENT.clear());
	}

	public static void refresh(MinecraftServer server) {
		Map<RegistryKey<World>, List<Mouth>> mouths = compute(server);
		for (ServerWorld world : server.getWorlds()) {
			RegistryKey<World> key = world.getRegistryKey();
			List<Mouth> current = mouths.getOrDefault(key, List.of());
			if (current.equals(SENT.getOrDefault(key, List.of()))) {
				continue;
			}
			SENT.put(key, current);
			var payload = new WormholeSyncPayload(current);
			for (ServerPlayerEntity player : PlayerLookup.world(world)) {
				send(player, payload);
			}
		}
	}

	/** Sends the mouths of the player's current dimension (joining, changing dimension, respawning). */
	public static void syncTo(ServerPlayerEntity player) {
		List<Mouth> mouths = compute(player.getServer()).getOrDefault(player.getServerWorld().getRegistryKey(), List.of());
		send(player, new WormholeSyncPayload(mouths));
	}

	private static Map<RegistryKey<World>, List<Mouth>> compute(MinecraftServer server) {
		Map<RegistryKey<World>, Set<Mouth>> mouths = new HashMap<>();
		for (ServerWorld source : server.getWorlds()) {
			for (BlackHoleData hole : BlackHoleManager.holes(source, false)) {
				var target = BlackHoleManager.whiteHoleOf(server, hole);
				if (target.isEmpty()) {
					continue;
				}
				RegistryKey<World> whiteDimension = target.get().world().getRegistryKey();
				boolean sameDimension = whiteDimension.equals(source.getRegistryKey());
				var black = hole.pos();
				var white = target.get().hole().pos();
				mouths.computeIfAbsent(source.getRegistryKey(), key -> new LinkedHashSet<>())
					.add(new Mouth(black, false, sameDimension ? Optional.of(white) : Optional.empty()));
				mouths.computeIfAbsent(whiteDimension, key -> new LinkedHashSet<>())
					.add(new Mouth(white, true, sameDimension ? Optional.of(black) : Optional.empty()));
			}
		}
		Map<RegistryKey<World>, List<Mouth>> result = new HashMap<>();
		mouths.forEach((key, set) -> result.put(key, List.copyOf(new ArrayList<>(set))));
		return result;
	}

	private static void send(ServerPlayerEntity player, WormholeSyncPayload payload) {
		if (ServerPlayNetworking.canSend(player, WormholeSyncPayload.ID)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
