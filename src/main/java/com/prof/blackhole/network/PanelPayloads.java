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
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

/** The packets of the control panel (the GUI opened with B, or by sneak-using a hole). */
public final class PanelPayloads {
	private static final int MAX_ENTRIES = 4096;
	private static final int MAX_TEXT = 64;

	private PanelPayloads() {
	}

	/** Client to server: send me the current state of every hole (answered with a {@link Snapshot}). */
	public record Request() implements CustomPayload {
		public static final CustomPayload.Id<Request> ID = new CustomPayload.Id<>(ProfsBlackHole.id("panel_request"));
		public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.unit(new Request());

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * One hole in the panel's list.
	 *
	 * @param dimension the hole's dimension id
	 * @param hole      its state
	 * @param feeding   whether it is accreting (a white hole: ejecting)
	 */
	public record Entry(String dimension, BlackHoleData hole, boolean feeding) {
	}

	/**
	 * Server to client: every hole in every dimension, or ({@code allowed == false}) the refusal of a player who may
	 * not use the panel.
	 *
	 * @param message feedback on the last action, shown in the panel (empty for none)
	 */
	public record Snapshot(boolean allowed, List<Entry> entries, Text message, boolean error) implements CustomPayload {
		public static final CustomPayload.Id<Snapshot> ID = new CustomPayload.Id<>(ProfsBlackHole.id("panel_snapshot"));
		public static final PacketCodec<RegistryByteBuf, Snapshot> CODEC = PacketCodec.of(
			(payload, buf) -> {
				buf.writeBoolean(payload.allowed);
				buf.writeVarInt(payload.entries.size());
				for (Entry entry : payload.entries) {
					PacketCodecs.STRING.encode(buf, entry.dimension);
					BlackHoleData.PACKET_CODEC.encode(buf, entry.hole);
					buf.writeBoolean(entry.feeding);
				}
				TextCodecs.REGISTRY_PACKET_CODEC.encode(buf, payload.message);
				buf.writeBoolean(payload.error);
			},
			buf -> {
				boolean allowed = buf.readBoolean();
				int count = buf.readVarInt();
				if (count < 0 || count > MAX_ENTRIES) {
					throw new DecoderException("Panel snapshot with " + count + " entries");
				}
				List<Entry> entries = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					entries.add(new Entry(PacketCodecs.STRING.decode(buf), BlackHoleData.PACKET_CODEC.decode(buf), buf.readBoolean()));
				}
				return new Snapshot(allowed, List.copyOf(entries), TextCodecs.REGISTRY_PACKET_CODEC.decode(buf), buf.readBoolean());
			}
		);

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** What the panel asks the server to do to a hole. */
	public enum ActionType {
		SET_MASS, SET_SPIN, SET_TEMPERATURE, SET_SCALE, SET_PULL_RADIUS,
		SET_DISK, SET_FROZEN, SET_STABLE,
		/** {@code text}: the white hole's id, or empty to close the wormhole. */
		LINK,
		REMOVE, TELEPORT_TO, MOVE_HERE,
		/** No target; a new black ({@code value == 0}) or white hole in front of the player. */
		SPAWN;

		private static final ActionType[] VALUES = values();

		static ActionType byOrdinal(int ordinal) {
			if (ordinal < 0 || ordinal >= VALUES.length) {
				throw new DecoderException("Unknown panel action " + ordinal);
			}
			return VALUES[ordinal];
		}
	}

	/**
	 * Client to server: change a hole.
	 *
	 * @param id    the hole's id (unused for {@link ActionType#SPAWN})
	 * @param value the new number or flag (flags: 0 / 1)
	 * @param text  extra text argument (the white hole of {@link ActionType#LINK})
	 */
	public record Action(String id, ActionType action, double value, String text) implements CustomPayload {
		public static final CustomPayload.Id<Action> ID = new CustomPayload.Id<>(ProfsBlackHole.id("panel_action"));
		public static final PacketCodec<RegistryByteBuf, Action> CODEC = PacketCodec.of(
			(payload, buf) -> {
				PacketCodecs.string(MAX_TEXT).encode(buf, payload.id);
				buf.writeVarInt(payload.action.ordinal());
				buf.writeDouble(payload.value);
				PacketCodecs.string(MAX_TEXT).encode(buf, payload.text);
			},
			buf -> new Action(PacketCodecs.string(MAX_TEXT).decode(buf), ActionType.byOrdinal(buf.readVarInt()), buf.readDouble(),
				PacketCodecs.string(MAX_TEXT).decode(buf))
		);

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}
}
