package com.prof.blackhole.entity;

import com.prof.blackhole.ProfsBlackHole;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;

public final class BlackHoleStatusEffects {
	/** Radiation sickness from Hawking radiation ({@link RadiationSicknessEffect}). */
	public static final RegistryEntry<StatusEffect> RADIATION = Registry.registerReference(Registries.STATUS_EFFECT,
		ProfsBlackHole.id("radiation"), new RadiationSicknessEffect());

	private BlackHoleStatusEffects() {
	}

	/** Loads the class, registering the effects. */
	public static void register() {
	}
}
