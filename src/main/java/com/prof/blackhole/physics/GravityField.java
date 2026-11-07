package com.prof.blackhole.physics;

import com.prof.blackhole.data.BlackHoleData;
import net.minecraft.util.math.Vec3d;

/**
 * One tick of gravity from every hole at once, shared by the server (for entities) and the client (which moves its
 * own player, so the pull is felt without network lag): Newtonian-limit attraction toward black holes
 * (a = GM_game / r^2), the same repulsion away from white holes, and equatorial Lense–Thirring frame dragging
 * (prograde around a black hole, retrograde around a white hole, whose negative-mass Kerr field drags the other
 * way).
 */
public final class GravityField {
	/** Maximum velocity change applied per tick, blocks/tick. */
	public static final double MAX_DELTA_V = 1.5;
	/** Maximum resulting entity speed, blocks/tick. */
	public static final double MAX_SPEED = 3.0;
	/** Fraction of the gap to the co-rotating (frame-dragged) velocity closed per tick. */
	private static final double FRAME_DRAG_COUPLING = 0.1;

	/**
	 * @param velocity     the new velocity
	 * @param acceleration summed magnitude of the pulls and pushes, blocks/tick^2 (0 outside every hole's reach)
	 */
	public record Pull(Vec3d velocity, double acceleration) {
	}

	private GravityField() {
	}

	/**
	 * @param holes       the holes of the dimension; frozen ones exert nothing
	 * @param bodyCenter  centre of the body's bounding box
	 * @param coupling    pull multiplier (End mobs couple more strongly)
	 * @param ignoreWhite true for matter a white hole has just ejected, which leaves on its own momentum
	 */
	public static Pull apply(Iterable<BlackHoleData> holes, Vec3d bodyCenter, Vec3d velocity, double coupling, boolean ignoreWhite) {
		double dvx = 0.0;
		double dvy = 0.0;
		double dvz = 0.0;
		double total = 0.0;
		for (BlackHoleData hole : holes) {
			if (hole.frozen() || (ignoreWhite && hole.white())) {
				continue;
			}
			double tx = hole.x() - bodyCenter.x;
			double ty = hole.y() - bodyCenter.y;
			double tz = hole.z() - bodyCenter.z;
			double r = Math.sqrt(tx * tx + ty * ty + tz * tz);
			double influence = hole.influenceRadius();
			if (r < 1.0e-4 || r > influence) {
				continue;
			}
			double rs = hole.schwarzschildRadius();
			double sign = hole.white() ? -1.0 : 1.0;
			double acceleration = Relativity.gravitationalAcceleration(rs, r) * coupling;
			double k = sign * acceleration / r;
			dvx += tx * k;
			dvy += ty * k;
			dvz += tz * k;
			total += acceleration;

			if (hole.spin() > 0.0) {
				double rx = -tx;
				double rz = -tz;
				double rho = Math.sqrt(rx * rx + rz * rz);
				if (rho > 1.0e-4) {
					// Prograde tangent normalize(cross(+Y, r)) = (rz, 0, -rx) / rho; reversed for a white hole.
					double ux = sign * rz / rho;
					double uz = sign * -rx / rho;
					double coRotatingSpeed = Relativity.frameDraggingOmega(rs, hole.spin(), r) * rho;
					double tangentialSpeed = velocity.x * ux + velocity.z * uz;
					if (tangentialSpeed < coRotatingSpeed) {
						double gap = (coRotatingSpeed - tangentialSpeed) * FRAME_DRAG_COUPLING;
						dvx += ux * gap;
						dvz += uz * gap;
					}
				}
			}
		}
		if (total == 0.0) {
			return new Pull(velocity, 0.0);
		}
		double deltaLength = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
		if (deltaLength > MAX_DELTA_V) {
			double k = MAX_DELTA_V / deltaLength;
			dvx *= k;
			dvy *= k;
			dvz *= k;
		}
		Vec3d newVelocity = velocity.add(dvx, dvy, dvz);
		double speed = newVelocity.length();
		if (speed > MAX_SPEED) {
			newVelocity = newVelocity.multiply(MAX_SPEED / speed);
		}
		return new Pull(newVelocity, total);
	}
}
