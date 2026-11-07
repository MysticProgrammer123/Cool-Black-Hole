package com.prof.blackhole.network;

import com.prof.blackhole.ProfsBlackHole;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.Vec3d;

/**
 * Server-to-client list of the wormhole mouths in the receiving player's dimension, for the client to draw: white
 * holes, and the beam (same dimension) or tunnel (across dimensions) of every black hole-white hole pair.
 */
public record WormholeSyncPayload(List<Mouth> mouths) implements CustomPayload {
	public static final CustomPayload.Id<WormholeSyncPayload> ID = new CustomPayload.Id<>(ProfsBlackHole.id("wormholes"));
	/** A sane cap for the decoder: two mouths per wormhole, at most a few dozen holes per dimension. */
	private static final int MAX_MOUTHS = 1024;

	/**
	 * One end of a wormhole.
	 *
	 * @param pos     position of the mouth
	 * @param white   true for the white hole end, false for the black hole end
	 * @param partner the other mouth, when it is in the same dimension
	 */
	public record Mouth(Vec3d pos, boolean white, Optional<Vec3d> partner) {
	}

	public static final PacketCodec<RegistryByteBuf, WormholeSyncPayload> CODEC = PacketCodec.of(
		(payload, buf) -> {
			buf.writeVarInt(payload.mouths.size());
			for (Mouth mouth : payload.mouths) {
				writeVec(buf, mouth.pos);
				buf.writeBoolean(mouth.white);
				buf.writeBoolean(mouth.partner.isPresent());
				mouth.partner.ifPresent(partner -> writeVec(buf, partner));
			}
		},
		buf -> {
			int count = buf.readVarInt();
			if (count < 0 || count > MAX_MOUTHS) {
				throw new io.netty.handler.codec.DecoderException("Wormhole sync with " + count + " mouths");
			}
			List<Mouth> mouths = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				Vec3d pos = readVec(buf);
				boolean white = buf.readBoolean();
				Optional<Vec3d> partner = buf.readBoolean() ? Optional.of(readVec(buf)) : Optional.empty();
				mouths.add(new Mouth(pos, white, partner));
			}
			return new WormholeSyncPayload(List.copyOf(mouths));
		}
	);

	private static void writeVec(RegistryByteBuf buf, Vec3d vec) {
		buf.writeDouble(vec.x);
		buf.writeDouble(vec.y);
		buf.writeDouble(vec.z);
	}

	private static Vec3d readVec(RegistryByteBuf buf) {
		return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
