package com.prof.blackhole.client.gui;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.data.BlackHoleData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Ways to open the control panel: the key (B by default, rebindable under Controls &rarr; Black Hole), or using an
 * empty hand or any item on a hole while sneaking, which opens it on that hole. Only for players who may use it
 * (operators, or the host of a singleplayer world in Creative); the server checks again for every request.
 */
public final class ControlPanelClient {
	/** Holes can be picked out this far away, blocks. */
	private static final double PICK_RANGE = 160.0;
	private static KeyBinding key;

	private ControlPanelClient() {
	}

	public static void register() {
		key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.profs_black_hole.control_panel", InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_B, "key.categories.profs_black_hole"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.wasPressed()) {
				if (client.currentScreen == null) {
					tryOpen(client, null);
				}
			}
		});
	}

	/** The client's own view of the server's rule; the server has the final say. */
	public static boolean mayUse(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		return player != null && (player.hasPermissionLevel(2) || (client.isInSingleplayer() && player.isCreative()));
	}

	private static boolean tryOpen(MinecraftClient client, @Nullable String preselect) {
		if (!mayUse(client)) {
			if (client.player != null) {
				client.player.sendMessage(Text.translatable("gui.profs_black_hole.panel.denied").formatted(Formatting.RED), true);
			}
			return false;
		}
		HolePanelScreen.open(preselect);
		return true;
	}

	/**
	 * Called when the use key is pressed (from {@code MinecraftClientMixin}): a sneaking player looking at a hole,
	 * with nothing nearer under the crosshair, opens the panel on it. Returns whether the use was taken.
	 */
	public static boolean onUse(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || !player.isSneaking() || client.currentScreen != null || ClientBlackHoleState.isEmpty() || !mayUse(client)) {
			return false;
		}
		Vec3d eye = player.getCameraPosVec(1.0F);
		Vec3d look = player.getRotationVec(1.0F);
		String picked = null;
		double best = PICK_RANGE;
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			// What the player sees: a black hole's shadow (~1.3 r_s), a white hole's core and its glare.
			double radius = Math.max(hole.white() ? 1.2 * hole.horizonRadius() : 1.35 * hole.schwarzschildRadius(), 0.8);
			Vec3d oc = eye.subtract(hole.pos());
			double b = oc.dotProduct(look);
			double c = oc.lengthSquared() - radius * radius;
			double disc = b * b - c;
			if (disc < 0.0) {
				continue;
			}
			double sq = Math.sqrt(disc);
			double t = -b - sq >= 0.0 ? -b - sq : -b + sq;
			if (t >= 0.0 && t < best) {
				best = t;
				picked = hole.id();
			}
		}
		if (picked == null) {
			return false;
		}
		HitResult hit = client.crosshairTarget;
		if (hit != null && hit.getType() != HitResult.Type.MISS && hit.getPos().distanceTo(eye) < best) {
			return false; // a block or entity in front of the hole gets the click
		}
		return tryOpen(client, picked);
	}
}
