package com.prof.blackhole.entity;

import com.prof.blackhole.ProfsBlackHole;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;

/**
 * Damage types of the mod. The entries themselves are data driven
 * ({@code data/profs_black_hole/damage_type/*.json}); vanilla tags in {@code data/minecraft/tags/damage_type}
 * give {@link #SINGULARITY} the same armor / invulnerability / resistance bypasses as the void, and
 * {@link #RADIATION} the armor bypass of magic.
 */
public final class BlackHoleDamageTypes {
	/** Event-horizon capture and tidal spaghettification; death message key {@code death.attack.profs_black_hole.singularity}. */
	public static final RegistryKey<DamageType> SINGULARITY = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, ProfsBlackHole.id("singularity"));
	/** Radiation sickness from a quiescent hole's Hawking radiation; death message key {@code death.attack.profs_black_hole.radiation}. */
	public static final RegistryKey<DamageType> RADIATION = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, ProfsBlackHole.id("radiation"));

	private BlackHoleDamageTypes() {
	}

	/**
	 * A singularity damage source without attacker, so the death message names the entity's prime adversary
	 * ({@code .player} variant) when it was fleeing one.
	 */
	public static DamageSource singularity(World world) {
		return world.getDamageSources().create(SINGULARITY);
	}

	public static DamageSource radiation(World world) {
		return world.getDamageSources().create(RADIATION);
	}
}
