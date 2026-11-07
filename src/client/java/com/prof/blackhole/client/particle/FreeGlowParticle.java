package com.prof.blackhole.client.particle;

import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * A self-luminous particle on a straight, damped path that outlives the hole that emitted it: gravitational-wave
 * fronts, the flash of an evaporating hole, and the streaks of the Singularity Dimension.
 */
final class FreeGlowParticle extends SpriteBillboardParticle {
	private final Sprite[] sprites;
	private final double damping;

	FreeGlowParticle(ClientWorld world, Sprite[] sprites, double x, double y, double z, double vx, double vy, double vz,
			double damping, int maxAge, float scale) {
		super(world, x, y, z);
		this.sprites = sprites;
		this.damping = damping;
		this.velocityX = vx;
		this.velocityY = vy;
		this.velocityZ = vz;
		this.maxAge = maxAge;
		this.scale = scale;
		this.collidesWithWorld = false;
		this.gravityStrength = 0.0f;
		setSprite(sprites[0]);
	}

	/** A coloured particle at {@code pos} moving with {@code velocity}, losing {@code 1 - damping} of it per tick. */
	static FreeGlowParticle of(ClientWorld world, Sprite[] sprites, Vec3d pos, Vec3d velocity, double damping, int maxAge,
			float scale, float[] color) {
		FreeGlowParticle particle = new FreeGlowParticle(world, sprites, pos.x, pos.y, pos.z,
				velocity.x, velocity.y, velocity.z, damping, maxAge, scale);
		particle.setColor(color[0], color[1], color[2]);
		return particle;
	}

	@Override
	public void tick() {
		prevPosX = x;
		prevPosY = y;
		prevPosZ = z;
		if (age++ >= maxAge) {
			markDead();
			return;
		}
		x += velocityX;
		y += velocityY;
		z += velocityZ;
		velocityX *= damping;
		velocityY *= damping;
		velocityZ *= damping;
		setSprite(sprites[age * (sprites.length - 1) / maxAge]);
		setAlpha(1.0f - (float) age / maxAge);
	}

	@Override
	public ParticleTextureSheet getType() {
		return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
	}

	@Override
	protected int getBrightness(float tint) {
		return LightmapTextureManager.MAX_LIGHT_COORDINATE;
	}
}
