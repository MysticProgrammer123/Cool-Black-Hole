package com.prof.blackhole.client;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * How the holes change the player's surroundings, as seen from the camera: sounds slow and drop in pitch with the
 * listener's gravitational time dilation near black holes (and quicken and rise near white holes, where clocks run
 * fast), the sky fades to the black of space near a black hole, and near a white hole it is washed out by the
 * glare of the core.
 */
public final class GravityEnvironment {
	/** Lowest pitch factor, reached near a horizon (OpenAL needs a positive pitch). */
	private static final float MIN_PITCH_FACTOR = 0.35F;
	/** Highest pitch factor, near a white hole's core. */
	private static final float MAX_PITCH_FACTOR = 1.6F;
	/** Highest pitch handed to OpenAL (vanilla's own cap). */
	private static final float MAX_PITCH = 2.0F;
	/** The sky starts to darken within this distance of a black hole, blocks... */
	private static final double DARK_SKY_OUTER = 100.0;
	/** ...and is fully black within this fraction of it. */
	private static final double DARK_SKY_INNER_FRACTION = 0.5;
	/** The darkening reaches at least this many r_s beyond the disk for heavy holes. */
	private static final double DARK_SKY_DISK_MARGIN_RS = 10.0;
	/** A white hole's glare reaches this far, blocks (at least 12 r_s). */
	private static final double GLARE_OUTER = 64.0;

	private GravityEnvironment() {
	}

	/**
	 * The listener's clock rate relative to far away, from every hole whose influence the camera is in:
	 * dtau/dt = sqrt(1 - r_s / r) for black holes, sqrt(1 + r_s / r) for white holes. Every sound is played with
	 * its pitch (and tempo) scaled by this factor, in [0.35, 1.6]; 1 outside every hole's influence.
	 */
	public static float pitchFactor() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || ClientBlackHoleState.isEmpty()) {
			return 1.0F;
		}
		Vec3d camera = client.gameRenderer.getCamera().getPos();
		double factor = 1.0;
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			if (hole.frozen()) {
				continue;
			}
			double rs = hole.schwarzschildRadius();
			double r = camera.distanceTo(hole.pos());
			if (r > hole.influenceRadius()) {
				continue;
			}
			factor *= hole.white() ? Relativity.timeAccelerationFactor(rs, r) : Math.max(Relativity.timeDilationFactor(rs, r), MIN_PITCH_FACTOR);
		}
		return (float) MathHelper.clamp(factor, MIN_PITCH_FACTOR, MAX_PITCH_FACTOR);
	}

	/** Vanilla's pitch for a sound, time-shifted; interface sounds (the master category: clicks, toasts) are left alone. */
	public static float dilatePitch(SoundInstance sound, float pitch) {
		if (sound.getCategory() == SoundCategory.MASTER) {
			return pitch;
		}
		float factor = pitchFactor();
		return factor == 1.0F ? pitch : MathHelper.clamp(pitch * factor, 0.05F, MAX_PITCH);
	}

	/** 0 far from black holes, 1 where the sky is pitch black. */
	public static float skyDarkness(Vec3d cameraPos) {
		float darkness = 0.0F;
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			if (hole.white()) {
				continue;
			}
			double outer = Math.max(DARK_SKY_OUTER, (6.0 + DARK_SKY_DISK_MARGIN_RS) * hole.schwarzschildRadius());
			darkness = Math.max(darkness, falloff(cameraPos.distanceTo(hole.pos()), outer, outer * DARK_SKY_INNER_FRACTION));
		}
		return darkness;
	}

	public static float skyDarkness() {
		return skyDarkness(MinecraftClient.getInstance().gameRenderer.getCamera().getPos());
	}

	/** 0 far from white holes, 1 where their glare whites out the sky. */
	public static float skyGlare(Vec3d cameraPos) {
		float glare = 0.0F;
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			if (!hole.white()) {
				continue;
			}
			double outer = Math.max(GLARE_OUTER, 12.0 * hole.schwarzschildRadius());
			glare = Math.max(glare, falloff(cameraPos.distanceTo(hole.pos()), outer, outer * 0.25));
		}
		return glare;
	}

	private static float falloff(double r, double outer, double inner) {
		if (r >= outer) {
			return 0.0F;
		}
		double t = MathHelper.clamp((outer - r) / (outer - inner), 0.0, 1.0);
		return (float) (t * t * (3.0 - 2.0 * t));
	}
}
