package com.prof.blackhole.client.particle;

import com.prof.blackhole.data.BlackHoleData;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * A spark of the event-horizon vortex: light caught near the photon sphere, whirling round on a tilted orbit far
 * faster than the disk and spiralling down onto the horizon, reddening and fading as it sinks (gravitational
 * redshift). Many of them on randomly tilted planes make a swirling shell of light just outside the shadow.
 */
final class HorizonSwirlParticle extends BlackHoleParticle {
	/** Angular speed at the photon sphere, rad/tick. */
	private static final double OMEGA = 0.35;
	/** Fractional inward drift of the orbit radius per tick. */
	private static final double SINK = 0.012;

	private final double horizon;
	private final double ux;
	private final double uy;
	private final double uz;
	private final double wx;
	private final double wy;
	private final double wz;
	private final double startRadius;
	private double r;
	private double angle;

	/**
	 * @param normal unit normal of the orbit plane
	 * @param r      starting radius, blocks
	 */
	HorizonSwirlParticle(ClientWorld world, BlackHoleData hole, Sprite[] sprites, Vec3d normal, double r, double angle,
			int maxAge, float scale) {
		super(world, hole, hole.pos(), sprites, maxAge, scale);
		Vec3d helper = Math.abs(normal.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
		Vec3d u = normal.crossProduct(helper).normalize();
		Vec3d w = normal.crossProduct(u);
		this.ux = u.x;
		this.uy = u.y;
		this.uz = u.z;
		this.wx = w.x;
		this.wy = w.y;
		this.wz = w.z;
		this.horizon = hole.horizonRadius();
		this.startRadius = r;
		this.r = r;
		this.angle = angle;
		place();
		// Born on its orbit, not swept in from the centre on the first frame.
		this.prevPosX = this.x;
		this.prevPosY = this.y;
		this.prevPosZ = this.z;
		updateColor();
	}

	@Override
	protected boolean advance() {
		angle += OMEGA * Math.pow(startRadius / r, 1.5);
		r -= r * SINK;
		if (r < 1.02 * horizon) {
			return false;
		}
		place();
		updateColor();
		return true;
	}

	private void place() {
		double c = Math.cos(angle) * r;
		double s = Math.sin(angle) * r;
		setPos(centerX + ux * c + wx * s, centerY + uy * c + wy * s, centerZ + uz * c + wz * s);
	}

	/** White-hot near the photon sphere, sinking through orange into a dim red at the horizon. */
	private void updateColor() {
		double t = Math.max(Math.min((r - horizon) / Math.max(startRadius - horizon, 1.0e-3), 1.0), 0.0);
		setColor((float) (0.75 + 0.25 * t), (float) (0.18 + 0.72 * t), (float) (0.08 + 0.72 * t * t));
	}
}
