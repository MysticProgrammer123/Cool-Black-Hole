package com.prof.blackhole.world;

import net.minecraft.server.MinecraftServer;

/**
 * TPS protection: scales the per-tick work of block destruction and entity searches by how much headroom the
 * server has left in its 50 ms tick budget.
 */
public final class TickBudget {
	/** Below this average tick time, milliseconds, everything runs at full rate. */
	private static final float RELAXED_MSPT = 30.0F;
	/** At and above this (the 20 TPS limit), work is cut to {@link #MIN_SCALE}. */
	private static final float OVERLOADED_MSPT = 50.0F;
	private static final double MIN_SCALE = 0.1;

	private TickBudget() {
	}

	/** Fraction of the nominal per-tick work to do now, in [{@value #MIN_SCALE}, 1]. */
	public static double scale(MinecraftServer server) {
		float mspt = server.getAverageTickTime();
		if (mspt <= RELAXED_MSPT) {
			return 1.0;
		}
		if (mspt >= OVERLOADED_MSPT) {
			return MIN_SCALE;
		}
		return 1.0 - (1.0 - MIN_SCALE) * (mspt - RELAXED_MSPT) / (OVERLOADED_MSPT - RELAXED_MSPT);
	}
}
