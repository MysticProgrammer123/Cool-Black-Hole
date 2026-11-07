package com.prof.blackhole.network;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.data.BlackHoleData;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Server-to-client update of the holes (black and white) in the receiving player's current dimension.
 *
 * @param replace true: {@code holes} is the complete set (joining, changing dimension); false: an incremental
 *                update, where {@code holes} were added or changed and {@code removed} (ids) are gone
 * @param holes   holes added or changed, or all of them
 * @param removed ids of holes that no longer exist
 */
public record BlackHoleSyncPayload(boolean replace, List<BlackHoleData> holes, List<String> removed) implements CustomPayload {
	public static final CustomPayload.Id<BlackHoleSyncPayload> ID = new CustomPayload.Id<>(ProfsBlackHole.id("sync"));
	/** Decoder sanity cap; a dimension holds at most a few dozen holes. */
	private static final int MAX_ENTRIES = 1024;

	public static final PacketCodec<RegistryByteBuf, BlackHoleSyncPayload> CODEC = PacketCodec.of(
		(payload, buf) -> {
			buf.writeBoolean(payload.replace);
			buf.writeVarInt(payload.holes.size());
			for (BlackHoleData hole : payload.holes) {
				BlackHoleData.PACKET_CODEC.encode(buf, hole);
			}
			buf.writeVarInt(payload.removed.size());
			for (String id : payload.removed) {
				PacketCodecs.STRING.encode(buf, id);
			}
		},
		buf -> {
			boolean replace = buf.readBoolean();
			int count = checked(buf.readVarInt());
			List<BlackHoleData> holes = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				holes.add(BlackHoleData.PACKET_CODEC.decode(buf));
			}
			int removedCount = checked(buf.readVarInt());
			List<String> removed = new ArrayList<>(removedCount);
			for (int i = 0; i < removedCount; i++) {
				removed.add(PacketCodecs.STRING.decode(buf));
			}
			return new BlackHoleSyncPayload(replace, List.copyOf(holes), List.copyOf(removed));
		}
	);

	private static int checked(int count) {
		if (count < 0 || count > MAX_ENTRIES) {
			throw new DecoderException("Black hole sync with " + count + " entries");
		}
		return count;
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
