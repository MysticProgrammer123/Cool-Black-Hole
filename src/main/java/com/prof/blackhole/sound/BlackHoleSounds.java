package com.prof.blackhole.sound;

import com.prof.blackhole.ProfsBlackHole;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class BlackHoleSounds {
	/** Hearing range of the siren, blocks (fixed, independent of volume). */
	public static final float SIREN_RANGE = 160.0F;

	public static final SoundEvent GRAVITATIONAL_SIREN = register("gravitational_siren", SIREN_RANGE);

	private BlackHoleSounds() {
	}

	/** Loads the class, registering the sounds. */
	public static void register() {
	}

	private static SoundEvent register(String name, float range) {
		Identifier id = ProfsBlackHole.id(name);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id, range));
	}
}
