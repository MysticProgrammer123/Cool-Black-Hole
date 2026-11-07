package com.prof.blackhole.wormhole;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The white hole paired with a black hole: the other mouth of the wormhole, where swallowed matter is expelled.
 *
 * @param dimension dimension of the white hole
 * @param pos       position of the white hole
 */
public record WhiteHoleLink(RegistryKey<World> dimension, Vec3d pos) {
	private static final String KEY_DIMENSION = "Dimension";
	private static final String KEY_X = "X";
	private static final String KEY_Y = "Y";
	private static final String KEY_Z = "Z";

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString(KEY_DIMENSION, dimension.getValue().toString());
		nbt.putDouble(KEY_X, pos.x);
		nbt.putDouble(KEY_Y, pos.y);
		nbt.putDouble(KEY_Z, pos.z);
		return nbt;
	}

	@Nullable
	public static WhiteHoleLink fromNbt(NbtCompound nbt) {
		Identifier id = Identifier.tryParse(nbt.getString(KEY_DIMENSION));
		if (id == null || !nbt.contains(KEY_X, NbtElement.NUMBER_TYPE) || !nbt.contains(KEY_Y, NbtElement.NUMBER_TYPE)
			|| !nbt.contains(KEY_Z, NbtElement.NUMBER_TYPE)) {
			return null;
		}
		Vec3d pos = new Vec3d(nbt.getDouble(KEY_X), nbt.getDouble(KEY_Y), nbt.getDouble(KEY_Z));
		if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) {
			return null;
		}
		return new WhiteHoleLink(RegistryKey.of(RegistryKeys.WORLD, id), pos);
	}
}
