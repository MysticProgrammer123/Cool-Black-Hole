package com.prof.blackhole.client.particle;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.data.BlackHoleData;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Glowing, full-bright particle that follows a prescribed path around one hole instead of integrating its own
 * velocity, so it ignores blocks and vanilla particle gravity. It dies as soon as its hole moves or disappears, so no
 * stale orbits linger after a command; the slow mass changes of accretion and evaporation keep it alive, as do any
 * changes {@link #stillValid} accepts. While its hole is frozen it hangs motionless, frozen in time with it.
 */
abstract class BlackHoleParticle extends SpriteBillboardParticle {
	private static final int FADE_IN_TICKS = 4;
	/** Fraction of the lifetime over which the particle fades out. */
	private static final float FADE_OUT_FRACTION = 0.3f;

	private final Sprite[] sprites;
	private final String holeId;
	protected final double centerX;
	protected final double centerY;
	protected final double centerZ;

	protected BlackHoleParticle(ClientWorld world, BlackHoleData hole, Vec3d start, Sprite[] sprites, int maxAge, float scale) {
		super(world, start.x, start.y, start.z);
		this.sprites = sprites;
		this.holeId = hole.id();
		this.centerX = hole.x();
		this.centerY = hole.y();
		this.centerZ = hole.z();
		this.maxAge = maxAge;
		this.scale = scale;
		this.collidesWithWorld = false;
		this.gravityStrength = 0.0f;
		setSprite(sprites[0]);
		updateAlpha();
	}

	@Override
	public final void tick() {
		prevPosX = x;
		prevPosY = y;
		prevPosZ = z;
		BlackHoleData current = ClientBlackHoleState.byId(holeId);
		if (current == null || current.x() != centerX || current.y() != centerY || current.z() != centerZ || !stillValid(current)) {
			markDead();
			return;
		}
		if (current.frozen()) {
			return;
		}
		if (age++ >= maxAge || !advance()) {
			markDead();
			return;
		}
		// Same progression as the end rod: the large sprite first, shrinking to a point.
		setSprite(sprites[age * (sprites.length - 1) / maxAge]);
		updateAlpha();
	}

	/** Whether the particle still belongs to the (unmoved) hole after its other parameters changed. */
	protected boolean stillValid(BlackHoleData current) {
		return true;
	}

	/** Moves the particle one tick along its path with {@link #setPos}; returns false once the path has ended. */
	protected abstract boolean advance();

	private void updateAlpha() {
		float fadeIn = Math.min(1.0f, (age + 1) / (float) FADE_IN_TICKS);
		float fadeOut = Math.min(1.0f, (maxAge - age) / (FADE_OUT_FRACTION * maxAge));
		setAlpha(Math.max(0.0f, Math.min(fadeIn, fadeOut)));
	}

	@Override
	public ParticleTextureSheet getType() {
		return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
	}

	/** Hot gas and jets are self-luminous: never darkened by the light level. */
	@Override
	protected int getBrightness(float tint) {
		return LightmapTextureManager.MAX_LIGHT_COORDINATE;
	}
}
