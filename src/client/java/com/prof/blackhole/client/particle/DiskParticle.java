package com.prof.blackhole.client.particle;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * A clump of disk gas. Around a black hole: on a prograde circular Keplerian orbit, slowly spiralling inward and
 * plunging once it drifts inside the ISCO; orbit rate, temperature profile and colour mirror {@code black_hole.fsh},
 * so the particles move with the shader's disk texture rather than across it. Around a white hole: ejected gas,
 * swirling at the same rate but streaming outward, blue-white and cooling as it goes.
 * <p>
 * Angles use the shader's mapping: {@code x = sqrt(r^2 + a^2) sin(phi)}, {@code z = sqrt(r^2 + a^2) cos(phi)},
 * with phi increasing in the prograde sense.
 */
final class DiskParticle extends BlackHoleParticle {
	/** Shader ANIM_RATE (geometric time units M per real second) expressed per tick. */
	private static final double ANIM_RATE_PER_TICK = 12.0 / 20.0;
	/** Fractional inward drift of the orbit radius per tick (viscous accretion, exaggerated to be visible). */
	private static final double INFLOW = 0.0015;
	/** Inside the ISCO no stable orbit exists and the gas plunges. */
	private static final double PLUNGE_INFLOW = 0.03;
	/** Fractional outward drift per tick of a white hole's ejected gas. */
	private static final double OUTFLOW = 0.006;
	private static final float[] WHITE_TINT = {0.55f, 0.9f, 1.0f};

	private final boolean white;
	private final double m;
	private final double spin;
	private final double horizon;
	private final double rIscoM;
	private final double invPeak;
	private final double peakTemperature;
	private final double outer;
	/** Height above the equatorial plane as a fraction of r (the disk is thin, so this stays small). */
	private final double heightFraction;
	private double r;
	private double phi;

	DiskParticle(ClientWorld world, BlackHoleData hole, Sprite[] sprites, double r, double phi, double heightFraction,
			int maxAge, float scale) {
		super(world, hole, diskPosition(hole, r, phi, heightFraction), sprites, maxAge, scale);
		double rs = hole.schwarzschildRadius();
		this.white = hole.white();
		this.m = 0.5 * rs;
		this.spin = hole.spin();
		this.horizon = hole.horizonRadius();
		this.rIscoM = Relativity.iscoRadius(rs, spin) / m;
		// Normalise to the visible peak as the shader does: T(r) peaks at (49/36) r_isco, clamped into the disk.
		double rPeakM = Math.min(Math.max(1.3611 * rIscoM, 4.0), 12.0);
		this.invPeak = 1.0 / Math.max(ntProfile(rPeakM, rIscoM), 1.0e-6);
		this.peakTemperature = hole.temperature();
		this.outer = 6.6 * rs;
		this.heightFraction = heightFraction;
		this.r = r;
		this.phi = phi;
		updateColor();
	}

	@Override
	protected boolean stillValid(BlackHoleData current) {
		return current.accretionDisk();
	}

	@Override
	protected boolean advance() {
		double rM = r / m;
		// Prograde Kepler rate in a Kerr field, Omega = 1 / (r^3/2 + a) (geometric units), at the shader's time scale.
		phi += ANIM_RATE_PER_TICK / (rM * Math.sqrt(rM) + spin);
		if (white) {
			r += r * OUTFLOW;
			if (r > outer) {
				return false;
			}
		} else {
			r -= r * (rM < rIscoM ? PLUNGE_INFLOW : INFLOW);
			if (r < 1.05 * horizon) {
				return false;
			}
		}
		Vec3d pos = diskPosition(centerX, centerY, centerZ, m * spin, r, phi, heightFraction);
		setPos(pos.x, pos.y, pos.z);
		updateColor();
		return true;
	}

	static Vec3d diskPosition(BlackHoleData hole, double r, double phi, double heightFraction) {
		return diskPosition(hole.x(), hole.y(), hole.z(), 0.5 * hole.schwarzschildRadius() * hole.spin(), r, phi, heightFraction);
	}

	static Vec3d diskPosition(double cx, double cy, double cz, double a, double r, double phi, double heightFraction) {
		double horizontal = Math.sqrt(r * r + a * a);
		return new Vec3d(cx + horizontal * Math.sin(phi), cy + heightFraction * r, cz + horizontal * Math.cos(phi));
	}

	/** Blackbody colour of the local (rest-frame) temperature; hotter gas is also drawn brighter. */
	private void updateColor() {
		double rM = r / m;
		double tRel;
		if (white) {
			tRel = Math.pow(Math.max(rM / 4.0, 1.0), -0.75);
		} else {
			double q2 = (rM / rIscoM) * (rM / rIscoM);
			// The shader's faint, cooler floor stands in for the plunging gas inside the ISCO.
			tRel = Math.max(ntProfile(rM, rIscoM) * invPeak, 0.4 * Math.min(q2, 1.0 / (q2 * q2)));
		}
		float[] rgb = DiskColors.blackbody(tRel * peakTemperature);
		if (white) {
			for (int i = 0; i < 3; i++) {
				rgb[i] = 0.55f * rgb[i] + 0.45f * WHITE_TINT[i];
			}
		}
		float brightness = (float) (0.55 + 0.45 * Math.min(tRel, 1.0));
		setColor(rgb[0] * brightness, rgb[1] * brightness, rgb[2] * brightness);
	}

	/** Novikov–Thorne-like shape T ~ r^-3/4 (1 - sqrt(r_isco / r))^1/4 (shader ntProfile), r in units of M. */
	private static double ntProfile(double rM, double rIscoM) {
		double x = Math.max(1.0 - Math.sqrt(rIscoM / rM), 0.0);
		return Math.pow(rM, -0.75) * Math.sqrt(Math.sqrt(x));
	}
}
