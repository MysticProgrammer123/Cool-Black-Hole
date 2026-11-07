package com.prof.blackhole.client.particle;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Matter moving freely in a hole's field. Around a black hole it falls in: wisps of air, crumbs of terrain (drawn
 * with the block's own particle sprite, tinted and lit like vanilla block dust) and shreds torn off spaghettified
 * mobs, accelerating under the same pull as entities ({@link Relativity#gravitationalAcceleration}), dragged
 * prograde by a spinning hole, gone at the horizon. Around a white hole the same force pushes outward: gas blown
 * out of the core, decelerating as it climbs away.
 */
final class InflowParticle extends BlackHoleParticle {
	/** Cap on the pull, blocks/tick^2, so particles near the horizon do not jump past it in one tick. */
	private static final double MAX_PULL = 0.4;
	private static final double MAX_SPEED = 2.0;
	private static final double FRAME_DRAG_COUPLING = 0.1;

	private final double rs;
	private final double spin;
	private final double horizon;
	private final double sign;
	private final double influence;
	/** Drawn from the block atlas (terrain crumbs) rather than the particle atlas. */
	private final boolean terrain;
	private double vx;
	private double vy;
	private double vz;

	InflowParticle(ClientWorld world, BlackHoleData hole, Sprite[] sprites, Vec3d start, Vec3d velocity, int maxAge,
			float scale, boolean terrain) {
		super(world, hole, start, sprites, maxAge, scale);
		this.rs = hole.schwarzschildRadius();
		this.spin = hole.spin();
		this.horizon = hole.horizonRadius();
		this.sign = hole.white() ? -1.0 : 1.0;
		this.influence = hole.influenceRadius();
		this.terrain = terrain;
		this.vx = velocity.x;
		this.vy = velocity.y;
		this.vz = velocity.z;
	}

	@Override
	protected boolean advance() {
		double dx = centerX - x;
		double dy = centerY - y;
		double dz = centerZ - z;
		double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if ((sign > 0.0 && r < 1.05 * horizon) || r > influence || r < 1.0e-3) {
			return false;
		}
		double pull = sign * Math.min(Relativity.gravitationalAcceleration(rs, r), MAX_PULL) / r;
		vx += dx * pull;
		vy += dy * pull;
		vz += dz * pull;
		double rho = Math.sqrt(dx * dx + dz * dz);
		if (spin > 0.0 && rho > 1.0e-3) {
			// Prograde tangent normalize(cross(+Y, r)) with r = -d: (-dz, 0, dx) / rho; retrograde for a white hole.
			double tx = sign * -dz / rho;
			double tz = sign * dx / rho;
			double gap = Relativity.frameDraggingOmega(rs, spin, r) * rho - (vx * tx + vz * tz);
			if (gap > 0.0) {
				vx += tx * gap * FRAME_DRAG_COUPLING;
				vz += tz * gap * FRAME_DRAG_COUPLING;
			}
		}
		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (speed > MAX_SPEED) {
			double k = MAX_SPEED / speed;
			vx *= k;
			vy *= k;
			vz *= k;
		}
		setPos(x + vx, y + vy, z + vz);
		return true;
	}

	@Override
	public ParticleTextureSheet getType() {
		return terrain ? ParticleTextureSheet.TERRAIN_SHEET : super.getType();
	}

	/** Terrain crumbs are lit by the world like vanilla block dust; air and shreds glow. */
	@Override
	protected int getBrightness(float tint) {
		if (!terrain) {
			return super.getBrightness(tint);
		}
		BlockPos pos = BlockPos.ofFloored(x, y, z);
		return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
				? WorldRenderer.getLightmapCoordinates(world, pos) : 0;
	}
}
