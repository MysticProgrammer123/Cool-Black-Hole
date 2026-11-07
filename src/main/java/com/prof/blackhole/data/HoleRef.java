package com.prof.blackhole.data;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A reference to a hole in some dimension: a black hole's wormhole link to its white hole.
 *
 * @param dimension dimension the hole is in
 * @param id        the hole's id, unique across all dimensions
 */
public record HoleRef(RegistryKey<World> dimension, String id) {
	private static final String KEY_DIMENSION = "Dimension";
	private static final String KEY_ID = "Id";

	public static final PacketCodec<ByteBuf, HoleRef> PACKET_CODEC = PacketCodec.of(
		(ref, buf) -> {
			PacketCodecs.STRING.encode(buf, ref.dimension.getValue().toString());
			PacketCodecs.STRING.encode(buf, ref.id);
		},
		buf -> new HoleRef(dimension(PacketCodecs.STRING.decode(buf)), PacketCodecs.STRING.decode(buf))
	);

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString(KEY_DIMENSION, dimension.getValue().toString());
		nbt.putString(KEY_ID, id);
		return nbt;
	}

	@Nullable
	public static HoleRef fromNbt(NbtCompound nbt) {
		Identifier dimension = Identifier.tryParse(nbt.getString(KEY_DIMENSION));
		String id = nbt.getString(KEY_ID);
		if (dimension == null || !BlackHoleData.isValidId(id)) {
			return null;
		}
		return new HoleRef(RegistryKey.of(RegistryKeys.WORLD, dimension), id);
	}

	private static RegistryKey<World> dimension(String value) {
		Identifier id = Identifier.tryParse(value);
		return id == null ? World.OVERWORLD : RegistryKey.of(RegistryKeys.WORLD, id);
	}
}
