package com.prof.blackhole.client.particle;

import com.prof.blackhole.data.BlackHoleData;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * A quantum of Hawking radiation: it appears just outside the horizon and streams radially outward, decelerating
 * slightly as it climbs out of the well.
 */
final class HawkingParticle extends BlackHoleParticle {
	/** Fraction of the outward speed kept per tick (the climb out of the well). */
	private static final double DECELERATION = 0.985;

	private final double dirX;
	private final double dirY;
	private final double dirZ;
	private double r;
	private double speed;

	/**
	 * @param dir   unit direction away from the singularity
	 * @param r     starting distance from the singularity, blocks
	 * @param speed initial outward speed, blocks/tick
	 */
	HawkingParticle(ClientWorld world, BlackHoleData hole, Sprite[] sprites, Vec3d dir, double r, double speed,
			int maxAge, float scale) {
		super(world, hole, hole.pos().add(dir.multiply(r)), sprites, maxAge, scale);
		this.dirX = dir.x;
		this.dirY = dir.y;
		this.dirZ = dir.z;
		this.r = r;
		this.speed = speed;
	}

	@Override
	protected boolean advance() {
		r += speed;
		speed *= DECELERATION;
		setPos(centerX + dirX * r, centerY + dirY * r, centerZ + dirZ * r);
		return true;
	}
}
