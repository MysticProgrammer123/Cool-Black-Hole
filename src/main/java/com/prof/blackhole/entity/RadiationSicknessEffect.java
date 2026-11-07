package com.prof.blackhole.entity;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;

/**
 * Radiation sickness ({@code profs_black_hole:radiation}): like Wither, half a heart of damage every
 * {@code 40 >> amplifier} ticks, but as {@link BlackHoleDamageTypes#RADIATION}, which armor does not stop.
 * The swirling green-violet aura is drawn by {@link com.prof.blackhole.world.HawkingRadiation}, not by the
 * effect's own (disabled) potion particles.
 */
public final class RadiationSicknessEffect extends StatusEffect {
	/** Radioactive green, the colour of the effect icon and of tipped items. */
	public static final int COLOR = 0x8CFF5A;

	public RadiationSicknessEffect() {
		super(StatusEffectCategory.HARMFUL, COLOR);
	}

	@Override
	public boolean applyUpdateEffect(LivingEntity entity, int amplifier) {
		entity.damage(BlackHoleDamageTypes.radiation(entity.getWorld()), 1.0F);
		return true;
	}

	@Override
	public boolean canApplyUpdateEffect(int duration, int amplifier) {
		int interval = 40 >> amplifier;
		return interval <= 0 || duration % interval == 0;
	}
}
