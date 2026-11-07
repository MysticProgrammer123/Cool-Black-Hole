package com.prof.blackhole.data;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.network.BlackHoleSyncPayload;
import com.prof.blackhole.world.BlackHoleEffects;
import com.prof.blackhole.world.BlackHoleEvolution;
import com.prof.blackhole.wormhole.WhiteHoleLink;
import com.prof.blackhole.wormhole.WormholeSync;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side entry point for reading and mutating holes. Every dimension holds up to
 * {@value #MAX_HOLES_PER_DIMENSION} black and white holes, each with an id that is unique across all dimensions.
 * <p>
 * Every mutation is persisted at once; the clients in the affected dimension are sent the changes at the end of
 * the server tick, batched into one incremental update per dimension, so a hole changing several times in a tick
 * (or several holes growing at once) still costs a single packet.
 */
public final class BlackHoleManager {
	public static final int MAX_HOLES_PER_DIMENSION = 32;

	/** A hole together with the dimension it is in. */
	public record Located(ServerWorld world, BlackHoleData hole) {
		public HoleRef ref() {
			return new HoleRef(world.getRegistryKey(), hole.id());
		}
	}

	private static final class Pending {
		final Map<String, BlackHoleData> upserts = new LinkedHashMap<>();
		final Set<String> removals = new LinkedHashSet<>();
	}

	private static final Map<RegistryKey<World>, Pending> PENDING = new HashMap<>();

	private BlackHoleManager() {
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(handler.getPlayer()));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> syncTo(player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> syncTo(newPlayer));
		ServerTickEvents.END_SERVER_TICK.register(BlackHoleManager::flush);
		ServerLifecycleEvents.SERVER_STARTED.register(BlackHoleManager::migrate);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
	}

	// ------------------------------------------------------------------------------------------------------------
	// Queries
	// ------------------------------------------------------------------------------------------------------------

	/** Every hole of the dimension, black and white (read-only). */
	public static Collection<BlackHoleData> holes(ServerWorld world) {
		return BlackHoleState.get(world).holes();
	}

	/** The dimension's black holes ({@code white == false}) or white holes, as a snapshot. */
	public static List<BlackHoleData> holes(ServerWorld world, boolean white) {
		List<BlackHoleData> result = new ArrayList<>();
		for (BlackHoleData hole : holes(world)) {
			if (hole.white() == white) {
				result.add(hole);
			}
		}
		return result;
	}

	public static Optional<BlackHoleData> get(ServerWorld world, String id) {
		return BlackHoleState.get(world).hole(id);
	}

	/** The hole with this id in whichever dimension holds it. */
	public static Optional<Located> find(MinecraftServer server, String id) {
		for (ServerWorld world : server.getWorlds()) {
			var hole = get(world, id);
			if (hole.isPresent()) {
				return Optional.of(new Located(world, hole.get()));
			}
		}
		return Optional.empty();
	}

	public static Optional<Located> resolve(MinecraftServer server, HoleRef ref) {
		ServerWorld world = server.getWorld(ref.dimension());
		if (world == null) {
			return Optional.empty();
		}
		return get(world, ref.id()).map(hole -> new Located(world, hole));
	}

	/** The white hole a black hole's wormhole leads to, if it still exists. */
	public static Optional<Located> whiteHoleOf(MinecraftServer server, BlackHoleData blackHole) {
		return blackHole.link().flatMap(ref -> resolve(server, ref)).filter(located -> located.hole().white());
	}

	/** The black holes whose wormholes lead to the given white hole. */
	public static List<Located> linkedTo(MinecraftServer server, HoleRef whiteHole) {
		List<Located> result = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			for (BlackHoleData hole : holes(world)) {
				if (hole.link().filter(whiteHole::equals).isPresent()) {
					result.add(new Located(world, hole));
				}
			}
		}
		return result;
	}

	/** The hole nearest to {@code pos}; {@code white} null for either kind. */
	public static Optional<BlackHoleData> nearest(ServerWorld world, Vec3d pos, @Nullable Boolean white) {
		BlackHoleData best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (BlackHoleData hole : holes(world)) {
			if (white != null && hole.white() != white) {
				continue;
			}
			double distance = hole.pos().squaredDistanceTo(pos);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = hole;
			}
		}
		return Optional.ofNullable(best);
	}

	public static int count(ServerWorld world) {
		return holes(world).size();
	}

	public static boolean isIdTaken(MinecraftServer server, String id) {
		return find(server, id).isPresent();
	}

	/** The first free id of the form {@code bh<n>} (black) or {@code wh<n>} (white). */
	public static String nextId(MinecraftServer server, boolean white) {
		Set<String> taken = new HashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			for (BlackHoleData hole : holes(world)) {
				taken.add(hole.id());
			}
		}
		return freeId(taken, white);
	}

	private static String freeId(Set<String> taken, boolean white) {
		String prefix = white ? "wh" : "bh";
		for (int n = 1; ; n++) {
			String id = prefix + n;
			if (!taken.contains(id)) {
				return id;
			}
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Mutations
	// ------------------------------------------------------------------------------------------------------------

	/** Adds the hole, or replaces the hole with the same id, in {@code world}. */
	public static void put(ServerWorld world, BlackHoleData data) {
		BlackHoleState.get(world).put(data);
		Pending pending = PENDING.computeIfAbsent(world.getRegistryKey(), key -> new Pending());
		pending.removals.remove(data.id());
		pending.upserts.put(data.id(), data);
		WormholeSync.refresh(world.getServer());
	}

	/**
	 * Removes the hole. A removed white hole closes the wormholes of every black hole that led to it; a removed
	 * black hole takes its own wormhole with it.
	 */
	public static Optional<BlackHoleData> remove(ServerWorld world, String id) {
		BlackHoleData removed = BlackHoleState.get(world).remove(id);
		if (removed == null) {
			return Optional.empty();
		}
		Pending pending = PENDING.computeIfAbsent(world.getRegistryKey(), key -> new Pending());
		pending.upserts.remove(id);
		pending.removals.add(id);
		if (removed.white()) {
			HoleRef ref = new HoleRef(world.getRegistryKey(), id);
			for (Located located : linkedTo(world.getServer(), ref)) {
				put(located.world(), located.hole().withLink(Optional.empty()));
			}
		}
		WormholeSync.refresh(world.getServer());
		return Optional.of(removed);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Sync
	// ------------------------------------------------------------------------------------------------------------

	/**
	 * Sends the holes of the player's current dimension (replacing whatever the client had), their accretion state
	 * and the dimension's wormhole mouths to that player.
	 */
	public static void syncTo(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		List<BlackHoleData> holes = List.copyOf(holes(world));
		send(player, new BlackHoleSyncPayload(true, holes, List.of()));
		for (BlackHoleData hole : holes) {
			BlackHoleEffects.sendFeeding(player, hole, BlackHoleEvolution.isFeeding(world, hole));
		}
		WormholeSync.syncTo(player);
	}

	private static void flush(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		for (var entry : PENDING.entrySet()) {
			ServerWorld world = server.getWorld(entry.getKey());
			Pending pending = entry.getValue();
			if (world == null || (pending.upserts.isEmpty() && pending.removals.isEmpty())) {
				continue;
			}
			var payload = new BlackHoleSyncPayload(false, List.copyOf(pending.upserts.values()), List.copyOf(pending.removals));
			for (ServerPlayerEntity player : PlayerLookup.world(world)) {
				send(player, payload);
			}
		}
		PENDING.clear();
	}

	private static void send(ServerPlayerEntity player, BlackHoleSyncPayload payload) {
		// Clients without the mod cannot receive the payload; ServerFallbackVisuals draws the holes for them.
		if (ServerPlayNetworking.canSend(player, BlackHoleSyncPayload.ID)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Saves from before multiple holes
	// ------------------------------------------------------------------------------------------------------------

	/**
	 * Runs once every dimension is loaded. A single-hole save gave each dimension's hole the same provisional id,
	 * so duplicates are renamed; and the old white-hole link (a bare position) becomes a real white hole that the
	 * black hole's wormhole leads to.
	 */
	private static void migrate(MinecraftServer server) {
		Set<String> taken = new HashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			for (BlackHoleData hole : holes(world)) {
				taken.add(hole.id());
			}
		}
		Set<String> seen = new HashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			BlackHoleState state = BlackHoleState.get(world);
			for (BlackHoleData hole : List.copyOf(state.holes())) {
				if (seen.add(hole.id())) {
					continue;
				}
				String fresh = freeId(taken, hole.white());
				taken.add(fresh);
				seen.add(fresh);
				state.rename(hole.id(), fresh);
				ProfsBlackHole.LOGGER.info("Black Hole: renamed duplicate hole id '{}' in {} to '{}'", hole.id(),
					world.getRegistryKey().getValue(), fresh);
			}
		}
		for (ServerWorld world : server.getWorlds()) {
			BlackHoleState state = BlackHoleState.get(world);
			WhiteHoleLink legacy = state.legacyWhiteHole();
			String owner = state.legacyWhiteHoleOwner();
			if (legacy == null || owner == null) {
				continue;
			}
			ServerWorld target = server.getWorld(legacy.dimension());
			if (target == null) {
				continue; // that dimension is gone or not loaded; kept for a later start
			}
			state.clearLegacyWhiteHole();
			var blackHole = state.hole(owner);
			if (blackHole.isEmpty()) {
				continue;
			}
			String id = nextId(server, true);
			put(target, BlackHoleData.create(id, true, legacy.pos(), target.getTime()));
			put(world, blackHole.get().withLink(Optional.of(new HoleRef(target.getRegistryKey(), id))));
			ProfsBlackHole.LOGGER.info("Black Hole: converted the white hole of '{}' into white hole '{}' in {}", owner, id,
				target.getRegistryKey().getValue());
		}
	}
}
