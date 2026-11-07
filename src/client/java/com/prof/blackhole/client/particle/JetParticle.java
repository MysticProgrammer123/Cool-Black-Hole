package com.prof.blackhole.client.particle;

import com.prof.blackhole.data.BlackHoleData;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Plasma racing out along the spin axis in a relativistic jet: it leaves from just above the horizon, opens into a
 * narrow cone and winds around the axis with a twist that unwinds with distance (the field lines are wound up by
 * the rotating horizon).
 */
final class JetParticle extends BlackHoleParticle {
	/** Relative amplitude of the turbulent wobble of the distance from the axis. */
	private static final double TURBULENCE = 0.3;
	/** Wobbles per block travelled, times 2 pi. */
	private static final double TURBULENCE_WAVENUMBER = 0.35;

	private final double wobblePhase;
	/** +1 for the jet along +Y, -1 for the counter-jet. */
	private final double sign;
	private final double base;
	private final double rho0;
	private final double speed;
	private final double spread;
	private final double twist;
	private double distance;
	private double phi;

	/**
	 * @param base   axial distance from the singularity where the jet is launched, blocks
	 * @param rho0   initial distance from the axis, blocks
	 * @param speed  outward speed, blocks/tick
	 * @param spread growth of the distance from the axis per block travelled (cone half-angle tangent)
	 * @param twist  angular velocity about the axis at the base, rad/tick
	 */
	JetParticle(ClientWorld world, BlackHoleData hole, Sprite[] sprites, double sign, double base, double rho0,
			double phi, double speed, double spread, double twist, int maxAge, float scale) {
		super(world, hole, new Vec3d(hole.x() + rho0 * Math.sin(phi), hole.y() + sign * base, hole.z() + rho0 * Math.cos(phi)),
				sprites, maxAge, scale);
		this.sign = sign;
		this.base = base;
		this.rho0 = rho0;
		this.speed = speed;
		this.spread = spread;
		this.twist = twist;
		this.phi = phi;
		this.wobblePhase = world.random.nextDouble() * 2.0 * Math.PI;
	}

	@Override
	protected boolean stillValid(BlackHoleData current) {
		return current.accretionDisk();
	}

	@Override
	protected boolean advance() {
		distance += speed;
		phi += twist * base / (base + distance);
		// Turbulence: the plasma wobbles in and out of the cone (kink-like instability) as it flies.
		double rho = (rho0 + distance * spread) * (1.0 + TURBULENCE * Math.sin(distance * TURBULENCE_WAVENUMBER + wobblePhase));
		setPos(centerX + rho * Math.sin(phi), centerY + sign * (base + distance), centerZ + rho * Math.cos(phi));
		return true;
	}
}
