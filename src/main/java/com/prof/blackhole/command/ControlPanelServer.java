package com.prof.blackhole.command;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.command.HoleControl.HoleException;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.BlackHoleManager.Located;
import com.prof.blackhole.network.PanelPayloads;
import com.prof.blackhole.world.BlackHoleEvolution;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Server side of the control panel. Every request is checked against {@link #mayUse}, so a modified client gains
 * nothing; every change goes through {@link HoleControl}, exactly like the commands.
 */
public final class ControlPanelServer {
	private static final String KEY = "gui.profs_black_hole.panel.";
	/** New holes appear this far in front of the player, blocks. */
	private static final double SPAWN_DISTANCE = 16.0;

	private ControlPanelServer() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PanelPayloads.Request.ID,
			(payload, context) -> reply(context.player(), Text.empty(), false));
		ServerPlayNetworking.registerGlobalReceiver(PanelPayloads.Action.ID,
			(payload, context) -> handle(context.player(), payload));
	}

	/**
	 * Who may use the panel: operators (permission level 2, as for the commands), and in singleplayer the host in
	 * Creative even without cheats.
	 */
	public static boolean mayUse(ServerPlayerEntity player) {
		return player.hasPermissionLevel(2) || (player.isCreative() && player.getServer().isHost(player.getGameProfile()));
	}

	private static void handle(ServerPlayerEntity player, PanelPayloads.Action action) {
		if (!mayUse(player)) {
			reply(player, Text.empty(), true);
			return;
		}
		try {
			Text message = apply(player, action);
			reply(player, message, false);
		} catch (HoleException e) {
			reply(player, e.text(), true);
		}
	}

	private static Text apply(ServerPlayerEntity player, PanelPayloads.Action action) throws HoleException {
		ServerWorld world = player.getServerWorld();
		if (action.action() == PanelPayloads.ActionType.SPAWN) {
			boolean white = action.value() != 0.0;
			Vec3d pos = player.getEyePos().add(player.getRotationVec(1.0F).multiply(SPAWN_DISTANCE));
			BlackHoleData data = HoleControl.spawn(world, null, pos, white);
			log(player, "spawned " + data.id());
			return Text.translatable(KEY + "spawned", HoleControl.label(data));
		}
		Located target = HoleControl.requireAny(world, player.getPos(), action.id());
		double value = action.value();
		switch (action.action()) {
			case SET_MASS -> HoleControl.update(target, hole -> hole.withMass(value));
			case SET_SPIN -> HoleControl.update(target, hole -> hole.withSpin(value));
			case SET_TEMPERATURE -> HoleControl.update(target, hole -> hole.withTemperature(value));
			case SET_SCALE -> HoleControl.update(target, hole -> hole.withScale(value));
			case SET_PULL_RADIUS -> HoleControl.update(target, hole -> hole.withPullRadius(value));
			case SET_DISK -> HoleControl.update(target, hole -> hole.withAccretionDisk(value != 0.0));
			case SET_FROZEN -> {
				BlackHoleData data = HoleControl.update(target, hole -> hole.withFrozen(value != 0.0));
				return Text.translatable("command.profs_black_hole." + (data.frozen() ? "freeze" : "unfreeze"), HoleControl.label(data));
			}
			case SET_STABLE -> {
				BlackHoleData data = HoleControl.update(target, hole -> hole.withStable(value != 0.0));
				return Text.translatable("command.profs_black_hole." + (data.stable() ? "set.stable.enabled" : "set.stable.disabled"),
					HoleControl.label(data));
			}
			case LINK -> {
				if (action.text().isEmpty()) {
					BlackHoleData data = HoleControl.unlink(target);
					log(player, "unlinked " + data.id());
					return Text.translatable("command.profs_black_hole.unlink", HoleControl.label(data));
				}
				Located whiteHole = HoleControl.require(world, player.getPos(), action.text(), true);
				BlackHoleData data = HoleControl.link(target, whiteHole);
				log(player, "linked " + data.id() + " to " + whiteHole.hole().id());
				return Text.translatable("command.profs_black_hole.link", HoleControl.label(data), HoleControl.label(whiteHole.hole()),
					whiteHole.world().getRegistryKey().getValue().toString());
			}
			case REMOVE -> {
				HoleControl.remove(target);
				log(player, "removed " + target.hole().id());
				return Text.translatable("command.profs_black_hole.destroy.success", HoleControl.label(target.hole()));
			}
			case TELEPORT_TO -> {
				HoleControl.teleportTo(player, target);
				return Text.translatable("command.profs_black_hole.teleport", HoleControl.label(target.hole()),
					target.world().getRegistryKey().getValue().toString());
			}
			case MOVE_HERE -> {
				if (target.world() != world) {
					throw new HoleException(Text.translatable(KEY + "other_dimension"));
				}
				Vec3d pos = player.getEyePos().add(player.getRotationVec(1.0F).multiply(SPAWN_DISTANCE));
				BlackHoleData data = HoleControl.move(target, pos);
				log(player, "moved " + data.id());
				return Text.translatable("command.profs_black_hole.set.position", HoleControl.label(data),
					HoleControl.fmt(data.x(), 2), HoleControl.fmt(data.y(), 2), HoleControl.fmt(data.z(), 2));
			}
			default -> {
			}
		}
		// Slider and toggle edits are frequent; the panel shows the new values themselves.
		return Text.empty();
	}

	/** Sends the player every hole of every dimension, with the outcome of their last action. */
	private static void reply(ServerPlayerEntity player, Text message, boolean error) {
		if (!ServerPlayNetworking.canSend(player, PanelPayloads.Snapshot.ID)) {
			return;
		}
		boolean allowed = mayUse(player);
		List<PanelPayloads.Entry> entries = new ArrayList<>();
		if (allowed) {
			for (ServerWorld world : player.getServer().getWorlds()) {
				for (BlackHoleData hole : BlackHoleManager.holes(world)) {
					entries.add(new PanelPayloads.Entry(world.getRegistryKey().getValue().toString(), hole,
						BlackHoleEvolution.isFeeding(world, hole)));
				}
			}
		}
		ServerPlayNetworking.send(player, new PanelPayloads.Snapshot(allowed, entries, message, error));
	}

	private static void log(ServerPlayerEntity player, String what) {
		ProfsBlackHole.LOGGER.info("Black Hole control panel: {} {}", player.getName().getString(), what);
	}
}
