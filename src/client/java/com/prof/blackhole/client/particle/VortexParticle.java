package com.prof.blackhole.client.particle;

import com.prof.blackhole.data.BlackHoleData;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;

/**
 * Gas in the vortex outside the visible disk, along one of two spiral arms. Around a black hole it spirals in: each
 * clump orbits at the prograde Kepler rate of its radius (so it whirls faster and faster as it falls), scaled by a
 * spin-up factor that grows while the hole is feeding, and heats up on the way, dull red far out, white-hot by the
 * time it reaches the disk. Around a white hole the same arms stream outward (negative {@code inflow}), cooling from
 * blue-white to deep blue.
 */
final class VortexParticle extends BlackHoleParticle {
	/** Shader ANIM_RATE (geometric time units M per real second) expressed per tick, as for {@link DiskParticle}. */
	private static final double ANIM_RATE_PER_TICK = 12.0 / 20.0;
	/** The gas is coolest (relative to the disk peak) at this many M from the hole... */
	private static final double REFERENCE_RADIUS_M = 6.0;
	private static final float[] WHITE_TINT = {0.5f, 0.85f, 1.0f};

	private final boolean white;
	private final double m;
	private final double spin;
	private final double horizon;
	private final double outer;
	private final double spinUp;
	private final double inflow;
	private final double heightFraction;
	private final double peakTemperature;
	private double r;
	private double phi;

	/**
	 * @param spinUp multiplier on the Kepler rate (1 = Keplerian)
	 * @param inflow fractional inward drift of the radius per tick (negative: outward)
	 */
	VortexParticle(ClientWorld world, BlackHoleData hole, Sprite[] sprites, double r, double phi, double heightFraction,
			double spinUp, double inflow, int maxAge, float scale) {
		super(world, hole, DiskParticle.diskPosition(hole, r, phi, heightFraction), sprites, maxAge, scale);
		double rs = hole.schwarzschildRadius();
		this.white = hole.white();
		this.m = 0.5 * rs;
		this.spin = hole.spin();
		this.horizon = hole.horizonRadius();
		this.outer = 14.0 * rs;
		this.spinUp = spinUp;
		this.inflow = inflow;
		this.heightFraction = heightFraction;
		this.peakTemperature = hole.temperature();
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
		phi += spinUp * ANIM_RATE_PER_TICK / (rM * Math.sqrt(rM) + spin);
		r -= r * inflow;
		if (r < 1.05 * horizon || r > outer) {
			return false;
		}
		var pos = DiskParticle.diskPosition(centerX, centerY, centerZ, m * spin, r, phi, heightFraction);
		setPos(pos.x, pos.y, pos.z);
		updateColor();
		return true;
	}

	private void updateColor() {
		// T ~ r^-3/4 relative to the disk peak, cooler than the disk itself out here.
		double tRel = Math.min(Math.pow(r / m / REFERENCE_RADIUS_M, -0.75), 1.2);
		float[] rgb = DiskColors.blackbody(0.55 * tRel * peakTemperature);
		if (white) {
			for (int i = 0; i < 3; i++) {
				rgb[i] = 0.5f * rgb[i] + 0.5f * WHITE_TINT[i];
			}
		}
		float brightness = (float) (0.45 + 0.4 * Math.min(tRel, 1.0));
		setColor(rgb[0] * brightness, rgb[1] * brightness, rgb[2] * brightness);
	}
}
