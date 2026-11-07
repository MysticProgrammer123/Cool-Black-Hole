package com.prof.blackhole.network;

import com.prof.blackhole.ProfsBlackHole;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.Vec3d;

/**
 * Server-to-client notification of a one-off hole event, or of a change in a hole's accretion state.
 *
 * @param kind     what happened
 * @param pos      where it happened (the hole, or the exit of a wormhole)
 * @param strength event-specific magnitude: shockwave strength, or 1 / 0 for {@link Kind#FEEDING}
 * @param id       the hole concerned, or empty when the event belongs to no hole
 */
public record BlackHoleEventPayload(Kind kind, Vec3d pos, float strength, String id) implements CustomPayload {
	public static final CustomPayload.Id<BlackHoleEventPayload> ID = new CustomPayload.Id<>(ProfsBlackHole.id("event"));

	public static final PacketCodec<RegistryByteBuf, BlackHoleEventPayload> CODEC = PacketCodec.of(
		(payload, buf) -> {
			buf.writeByte(payload.kind.ordinal());
			buf.writeDouble(payload.pos.x);
			buf.writeDouble(payload.pos.y);
			buf.writeDouble(payload.pos.z);
			buf.writeFloat(payload.strength);
			PacketCodecs.STRING.encode(buf, payload.id);
		},
		buf -> new BlackHoleEventPayload(Kind.byOrdinal(buf.readByte()),
			new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readFloat(), PacketCodecs.STRING.decode(buf))
	);

	public enum Kind {
		/** Spherical gravitational-wave burst (a hole formed, or swallowed a large mass). */
		SHOCKWAVE,
		/** Whether the hole is currently swallowing (a white hole: ejecting) matter; quiescent black holes glow with Hawking radiation. */
		FEEDING,
		/** The hole evaporated completely: a final flash. */
		EVAPORATED,
		/** An evaporating hole crossed a critical mass: a burst of Hawking radiation (strength ~ 1 / M). */
		HAWKING_FLARE,
		/** A white hole ran out of mass and blew apart in one last ejection. */
		DETONATION,
		/** Two holes merged: the ringdown of the new horizon. */
		MERGER;

		private static final Kind[] VALUES = values();

		static Kind byOrdinal(int ordinal) {
			return VALUES[Math.floorMod(ordinal, VALUES.length)];
		}
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
