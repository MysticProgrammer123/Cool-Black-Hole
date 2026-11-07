package com.prof.blackhole.client;

import com.prof.blackhole.network.WormholeSyncPayload;
import java.util.List;
import java.util.Objects;

/**
 * Client-side mirror of the wormhole mouths in the player's current dimension, as last synced through
 * {@link WormholeSyncPayload}. Same threading as {@link ClientBlackHoleState}: a volatile immutable list.
 */
public final class ClientWormholeState {
	private static volatile List<WormholeSyncPayload.Mouth> mouths = List.of();

	private ClientWormholeState() {
	}

	public static List<WormholeSyncPayload.Mouth> get() {
		return mouths;
	}

	public static void set(List<WormholeSyncPayload.Mouth> value) {
		mouths = List.copyOf(Objects.requireNonNull(value, "value"));
	}

	public static void clear() {
		mouths = List.of();
	}
}
