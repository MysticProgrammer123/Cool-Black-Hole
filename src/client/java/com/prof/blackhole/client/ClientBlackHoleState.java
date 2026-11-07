package com.prof.blackhole.client;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.network.BlackHoleSyncPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side mirror of the holes (black and white) in the player's current dimension, as last synced by the server
 * through {@link BlackHoleSyncPayload}, and of which of them are accreting
 * ({@link com.prof.blackhole.network.BlackHoleEventPayload.Kind#FEEDING}).
 * <p>
 * Written from the network receivers (render thread) and the disconnect event (possibly the netty thread), read
 * from the render thread; the hole list is published as an immutable snapshot through a volatile field.
 */
public final class ClientBlackHoleState {
	private static volatile List<BlackHoleData> holes = List.of();
	private static volatile Map<String, BlackHoleData> byId = Map.of();
	private static final Set<String> FEEDING = Collections.synchronizedSet(new HashSet<>());
	/** Shader time (seconds) at which each frozen hole stopped, so its disk stays still. */
	private static final Map<String, Float> FROZEN_AT = Collections.synchronizedMap(new HashMap<>());

	private ClientBlackHoleState() {
	}

	/** Every hole of the dimension (immutable snapshot). */
	public static List<BlackHoleData> all() {
		return holes;
	}

	public static boolean isEmpty() {
		return holes.isEmpty();
	}

	@Nullable
	public static BlackHoleData byId(String id) {
		return byId.get(id);
	}

	public static Optional<BlackHoleData> nearest(Vec3d pos) {
		BlackHoleData best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (BlackHoleData hole : holes) {
			double distance = hole.pos().squaredDistanceTo(pos);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = hole;
			}
		}
		return Optional.ofNullable(best);
	}

	public static void apply(BlackHoleSyncPayload payload) {
		Map<String, BlackHoleData> next = payload.replace() ? new LinkedHashMap<>() : new LinkedHashMap<>(byId);
		for (String id : payload.removed()) {
			next.remove(id);
			FEEDING.remove(id);
		}
		for (BlackHoleData hole : payload.holes()) {
			next.put(hole.id(), hole);
		}
		if (payload.replace()) {
			FEEDING.retainAll(next.keySet());
		}
		FROZEN_AT.keySet().retainAll(next.keySet());
		byId = Collections.unmodifiableMap(next);
		holes = List.copyOf(new ArrayList<>(next.values()));
	}

	/** Whether the hole is swallowing matter (a white hole: ejecting); a quiescent black hole glows with Hawking radiation. */
	public static boolean isFeeding(String id) {
		return FEEDING.contains(id);
	}

	public static void setFeeding(String id, boolean value) {
		if (value) {
			FEEDING.add(id);
		} else {
			FEEDING.remove(id);
		}
	}

	/** The time the shader animates the hole's disk with: {@code now}, or for a frozen hole the moment it froze. */
	public static float shaderTime(BlackHoleData hole, float now) {
		if (!hole.frozen()) {
			FROZEN_AT.remove(hole.id());
			return now;
		}
		return FROZEN_AT.computeIfAbsent(hole.id(), id -> now);
	}

	public static void clear() {
		holes = List.of();
		byId = Map.of();
		FEEDING.clear();
		FROZEN_AT.clear();
	}
}
