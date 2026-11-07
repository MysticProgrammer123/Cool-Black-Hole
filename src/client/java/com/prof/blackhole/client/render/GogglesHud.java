package com.prof.blackhole.client.render;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * The goggles' readout, top left: the nearest hole's id and kind, distance, mass, r_s and spin, and what it does
 * where the player stands: the local clock rate dtau/dt (slowed near a black hole, sped up near a white hole), the
 * pull in units of vanilla gravity and the tidal stretch across the player's body.
 */
public final class GogglesHud {
	private static final double RANGE = 256.0;
	private static final double VANILLA_GRAVITY = 0.08;

	private GogglesHud() {
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden || !GogglesVision.isWorn(client)) {
			return;
		}
		Vec3d eye = client.player.getEyePos();
		var nearest = ClientBlackHoleState.nearest(eye);
		List<Text> lines = new ArrayList<>();
		if (nearest.isEmpty() || nearest.get().pos().distanceTo(eye) > RANGE) {
			lines.add(Text.translatable("hud.profs_black_hole.goggles.none").formatted(Formatting.DARK_AQUA));
		} else {
			BlackHoleData hole = nearest.get();
			double rs = hole.schwarzschildRadius();
			double r = hole.pos().distanceTo(eye);
			boolean inside = r < hole.influenceRadius() && !hole.frozen();
			double clock = hole.white() ? Relativity.timeAccelerationFactor(rs, r) : Relativity.timeDilationFactor(rs, r);
			double pull = inside ? Relativity.gravitationalAcceleration(rs, r) / VANILLA_GRAVITY : 0.0;
			double stretch = inside ? Relativity.tidalStretch(rs, r, client.player.getHeight()) : 1.0;
			lines.add(Text.translatable("hud.profs_black_hole.goggles.title",
				Text.translatable("command.profs_black_hole." + (hole.white() ? "label.white" : "label.black"), hole.id()),
				fmt(r, 1)).formatted(hole.white() ? Formatting.GOLD : Formatting.LIGHT_PURPLE));
			lines.add(Text.translatable("hud.profs_black_hole.goggles.body", fmt(hole.mass(), 3), fmt(rs, 2), fmt(hole.spin(), 2))
				.formatted(Formatting.AQUA));
			lines.add(Text.translatable("hud.profs_black_hole.goggles.local", fmt(clock, 3), fmt(pull, 2), fmt(stretch, 2))
				.formatted(Formatting.AQUA));
			if (hole.frozen()) {
				lines.add(Text.translatable("hud.profs_black_hole.goggles.frozen").formatted(Formatting.BLUE));
			}
		}
		int y = 6;
		for (Text line : lines) {
			context.drawTextWithShadow(client.textRenderer, line, 6, y, 0xFFFFFFFF);
			y += 10;
		}
	}

	private static String fmt(double value, int decimals) {
		return String.format(Locale.ROOT, "%." + decimals + "f", value);
	}
}
