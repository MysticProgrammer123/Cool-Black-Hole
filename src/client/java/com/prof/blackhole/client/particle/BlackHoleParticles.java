package com.prof.blackhole.client.particle;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.client.ClientConfig;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.physics.Relativity;
import com.prof.blackhole.world.HawkingRadiation;
import com.prof.blackhole.world.SpacetimeCorruption;
import com.prof.blackhole.wormhole.SingularityTransit;
import com.prof.blackhole.wormhole.WhiteHoleManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

/**
 * Spawns every hole's particle effects each client tick (the nearest {@value #MAX_PARTICLE_HOLES} holes in range).
 * <p>
 * <b>Black holes:</b>
 * <ul>
 *     <li>glowing gas orbiting in the accretion-disk plane ({@link DiskParticle}), fed by a two-armed vortex
 *     spiralling in from further out ({@link VortexParticle}) that whirls faster while the hole is feeding;</li>
 *     <li>the event-horizon vortex: sparks whirling round on tilted orbits at the photon sphere and spiralling down
 *     onto the horizon ({@link HorizonSwirlParticle});</li>
 *     <li>for spinning holes, twin turbulent jets along the polar axis ({@link JetParticle}) that pulse in
 *     brightness, fire bright energy knots outward and crackle with sparks, flame and ring-shaped shocks;</li>
 *     <li>streams of air and crumbs of the surrounding terrain falling in from every direction
 *     ({@link InflowParticle}), and shreds and sparks torn off spaghettified mobs and players;</li>
 *     <li>for a quiescent hole, blue-violet Hawking radiation ({@link HawkingParticle}) and green-violet sparkles
 *     marking the edge of its radiation zone;</li>
 *     <li>for a long-standing hole, cosmic tendrils reaching from its corruption zone into it.</li>
 * </ul>
 * <b>White holes:</b> a blazing core with white and cyan outbursts and two precessing ejection rings; an outflowing
 * blue-white disk and vortex; golden jets; gas blown out in every direction; and, every
 * {@value WhiteHoleManager#REPULSION_PULSE_INTERVAL} ticks in step with the server's push, an explosive ejection
 * pulse of shell, ring, jets of light and sparks.
 * <p>
 * Disk, vortex and jets stop while the disk is disabled, and nothing is spawned for a frozen hole. Also draws
 * wormholes ({@link WormholeParticles}), the one-off events (gravitational-wave bursts, Hawking flares, evaporation,
 * white-hole detonations, mergers) and the light-streak tunnel of the Singularity Dimension.
 * <p>
 * The particles are handed to the {@link ParticleManager} directly instead of being registered as particle types,
 * which also bypasses vanilla's 32-block spawn cut-off. They are drawn into the world before the lensing pass, so
 * they are lensed along with everything else.
 */
public final class BlackHoleParticles {
	/** Disk extent drawn by the shader (DISK_IN_M / DISK_OUT_M = 4 M / 12 M), in r_s. */
	private static final double DISK_INNER_RS = 2.0;
	private static final double DISK_OUTER_RS = 6.0;
	/** Half thickness of the particle layer as a fraction of r. */
	private static final double DISK_HALF_THICKNESS = 0.03;
	private static final double DISK_PARTICLES_PER_TICK = 9.0;
	/** The accretion vortex spirals in from this many r_s to the disk. */
	private static final double VORTEX_OUTER_RS = 12.0;
	private static final double VORTEX_PARTICLES_PER_TICK = 8.0;
	private static final double HORIZON_SWIRL_PER_TICK = 6.0;
	/** Jet particles per tick and jet at maximal spin; jet power scales as a*^2 (Blandford–Znajek). */
	private static final double JET_PARTICLES_PER_TICK = 7.0;
	private static final double JET_SPARKS_PER_TICK = 3.5;
	private static final double JET_FLAMES_PER_TICK = 2.0;
	/** Shock knots (internal shocks: rings flashing out from the jet) per tick and jet at maximal spin. */
	private static final double JET_KNOTS_PER_TICK = 0.1;
	/** Energy knots (bright plasmoids riding out along the jet) per tick and jet at maximal spin. */
	private static final double JET_PLASMOIDS_PER_TICK = 0.06;
	/** Jet brightness pulses with this angular frequency, rad/tick. */
	private static final double JET_PULSE_RATE = 0.3;
	private static final int KNOT_PARTICLES = 16;
	private static final double JET_LENGTH_RS = 12.0;
	private static final double MAX_JET_LENGTH = 160.0;
	private static final double JET_SPREAD = 0.05;
	private static final double JET_TWIST = 0.3;
	private static final float[] JET_COLOR_INNER = {0.62f, 0.78f, 1.0f};
	private static final float[] JET_COLOR_OUTER = {0.85f, 0.9f, 1.0f};
	private static final float[] WHITE_JET_INNER = {1.0f, 0.86f, 0.5f};
	private static final float[] WHITE_JET_OUTER = {1.0f, 0.97f, 0.85f};
	/** Air streaming in from every direction, and crumbs of the terrain it tears loose, per tick. */
	private static final double AIR_PER_TICK = 7.0;
	private static final double CRUMBS_PER_TICK = 3.0;
	private static final int CRUMB_SAMPLES = 4;
	private static final float[] AIR_COLOR = {0.72f, 0.8f, 0.92f};
	/** Tidal stretch above which mobs visibly shed shreds, and the shreds per tick per unit of stretch beyond it. */
	private static final double TEAR_STRETCH = 1.6;
	private static final double TEARS_PER_STRETCH = 1.5;
	private static final double MAX_TEARS_PER_TICK = 6.0;
	/** Entities farther than this from the camera are not torn visibly, blocks. */
	private static final double TEAR_RANGE = 96.0;
	private static final float[] TEAR_RED = {0.78f, 0.08f, 0.1f};
	private static final float[] TEAR_DARK = {0.42f, 0.02f, 0.06f};
	private static final float[] TEAR_SPARK = {1.0f, 0.7f, 0.35f};
	/** Hawking quanta per tick from a 1 M_J hole; the power scales as 1 / M^2, the rate here more gently as 1 / M. */
	private static final double HAWKING_PER_TICK = 2.0;
	private static final double MAX_HAWKING_PER_TICK = 8.0;
	private static final double RADIATION_SPARKLES_PER_TICK = 4.0;
	private static final float[] HAWKING_BLUE = {0.45f, 0.62f, 1.0f};
	private static final float[] HAWKING_VIOLET = {0.72f, 0.45f, 1.0f};
	private static final float[] RADIATION_GREEN = {0.5f, 1.0f, 0.4f};
	private static final float[] SHOCKWAVE_COLOR = {0.75f, 0.86f, 1.0f};
	private static final float[] SHOCKWAVE_RING_COLOR = {1.0f, 0.92f, 0.8f};
	/** Cosmic tendrils from the corruption zone: chance per tick per hole, and particles along each. */
	private static final double TENDRIL_CHANCE = 0.3;
	private static final int TENDRIL_POINTS = 16;
	private static final float[] TENDRIL_DARK = {0.32f, 0.16f, 0.48f};
	private static final float[] TENDRIL_LIGHT = {0.7f, 0.45f, 1.0f};
	private static final double WHITE_OUTFLOW_PER_TICK = 6.0;
	private static final double RING_RADIUS_RS = 0.85;
	private static final double RING_TILT = Math.toRadians(35.0);
	private static final float[] WHITE = {1.0f, 1.0f, 1.0f};
	private static final float[] CYAN = {0.5f, 0.95f, 1.0f};
	private static final float[] GOLD = {1.0f, 0.82f, 0.45f};
	/** Ticks a crossing of the Singularity Dimension lasts, as on the server. */
	private static final double TRANSIT_TICKS = 100.0;
	/** Particles are only spawned while the camera is within the effects' extent plus this margin, blocks. */
	private static final double SPAWN_MARGIN = 128.0;
	/** Holes nearest the camera that spawn particles; the rest only cost their lensing pass. */
	private static final int MAX_PARTICLE_HOLES = 6;

	private static final Identifier[] SPRITE_IDS = new Identifier[8];

	static {
		for (int i = 0; i < SPRITE_IDS.length; i++) {
			SPRITE_IDS[i] = Identifier.ofVanilla("glitter_" + i);
		}
	}

	/** The Singularity Dimension world the player is crossing, and the world time they arrived. */
	@Nullable
	private static ClientWorld transitWorld;
	private static long transitStart;

	private BlackHoleParticles() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(BlackHoleParticles::tick);
	}

	private static void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		// The particle manager does not tick while paused; spawning then would only pile particles up.
		if (world == null || client.isPaused()) {
			return;
		}
		Sprite[] sprites = sprites(client);
		if (sprites == null) {
			return;
		}
		if (world.getRegistryKey().equals(SingularityTransit.DIMENSION)) {
			spawnSingularityStreaks(client, world, sprites);
			return;
		}
		transitWorld = null;

		double budget = budget(client);
		if (budget <= 0.0) {
			return;
		}
		ParticleManager particles = client.particleManager;
		Random random = world.random;
		Camera camera = client.gameRenderer.getCamera();
		Vec3d cam = camera.getPos();
		WormholeParticles.tick(particles, world, sprites, random, budget, cam);

		List<BlackHoleData> holes = new ArrayList<>();
		for (BlackHoleData hole : ClientBlackHoleState.all()) {
			double rs = hole.schwarzschildRadius();
			double extent = Math.max(VORTEX_OUTER_RS * rs, jetLength(rs));
			if (!hole.frozen() && cam.distanceTo(hole.pos()) <= extent + SPAWN_MARGIN) {
				holes.add(hole);
			}
		}
		holes.sort(Comparator.comparingDouble(hole -> hole.pos().squaredDistanceTo(cam)));
		for (int i = 0; i < Math.min(holes.size(), MAX_PARTICLE_HOLES); i++) {
			BlackHoleData hole = holes.get(i);
			double local = budget * (i == 0 ? 1.0 : 0.6);
			if (hole.white()) {
				spawnWhiteHole(particles, world, hole, sprites, random, local);
			} else {
				spawnBlackHole(client, particles, world, hole, sprites, random, local, camera);
			}
		}
	}

	private static double jetLength(double rs) {
		return Math.min(JET_LENGTH_RS * rs + 8.0, MAX_JET_LENGTH);
	}

	private static void spawnBlackHole(MinecraftClient client, ParticleManager particles, ClientWorld world, BlackHoleData hole,
			Sprite[] sprites, Random random, double budget, Camera camera) {
		double rs = hole.schwarzschildRadius();
		boolean feeding = ClientBlackHoleState.isFeeding(hole.id());
		if (hole.accretionDisk()) {
			spawnDisk(particles, world, hole, rs, sprites, random, budget);
			spawnVortex(particles, world, hole, rs, sprites, random, budget, feeding);
			spawnJets(particles, world, hole, rs, jetLength(rs), sprites, random, budget, JET_COLOR_INNER, JET_COLOR_OUTER);
		}
		spawnHorizonSwirl(particles, world, hole, rs, sprites, random, budget * (feeding ? 1.4 : 1.0));
		spawnInflow(client, particles, world, hole, rs, sprites, random, budget);
		spawnTearing(particles, world, hole, rs, sprites, random, budget, camera);
		if (!feeding && HawkingRadiation.radiates(hole)) {
			spawnHawking(particles, world, hole, rs, sprites, random, budget);
			spawnRadiationAura(particles, world, hole, sprites, random, budget);
		}
		double corruption = SpacetimeCorruption.radius(hole, world.getTime());
		if (corruption > 0.0) {
			spawnTendrils(particles, world, hole, corruption, sprites, random, budget);
		}
	}

	private static void spawnWhiteHole(ParticleManager particles, ClientWorld world, BlackHoleData hole, Sprite[] sprites,
			Random random, double budget) {
		double rs = hole.schwarzschildRadius();
		long time = world.getTime();
		spawnWhiteCore(particles, world, hole, rs, sprites, random, budget, time);
		if (hole.accretionDisk()) {
			spawnDisk(particles, world, hole, rs, sprites, random, budget);
			spawnVortex(particles, world, hole, rs, sprites, random, budget, ClientBlackHoleState.isFeeding(hole.id()));
			spawnJets(particles, world, hole, rs, jetLength(rs), sprites, random, budget, WHITE_JET_INNER, WHITE_JET_OUTER);
		}
		spawnOutflow(particles, world, hole, rs, sprites, random, budget);
		if (WhiteHoleManager.isPulseTick(time, hole.id())) {
			spawnEjectionPulse(particles, world, hole.pos(), rs, sprites, random, Math.max(budget, 0.3), 1.0);
		}
	}

	private static double budget(MinecraftClient client) {
		double base = switch (client.options.getParticles().getValue()) {
			case ALL -> 1.0;
			case DECREASED -> 0.4;
			case MINIMAL -> 0.0;
		};
		return base * ClientConfig.particles();
	}

	@SuppressWarnings("deprecation") // PARTICLE_ATLAS_TEXTURE: Mojang-deprecated, yet what vanilla's particle sheets bind
	@Nullable
	private static Sprite[] sprites(MinecraftClient client) {
		if (!(client.getTextureManager().getTexture(SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE) instanceof SpriteAtlasTexture atlas)) {
			return null;
		}
		Sprite[] sprites = new Sprite[SPRITE_IDS.length];
		for (int i = 0; i < sprites.length; i++) {
			sprites[i] = atlas.getSprite(SPRITE_IDS[i]);
		}
		return sprites;
	}

	private static void spawnDisk(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget) {
		double inner = hole.white() ? DISK_INNER_RS * rs : Math.max(DISK_INNER_RS * rs, Relativity.iscoRadius(rs, hole.spin()));
		double outer = DISK_OUTER_RS * rs;
		float scale = (float) Math.min(Math.max(0.07 * rs, 0.12), 3.0);
		int count = count(DISK_PARTICLES_PER_TICK * budget, random);
		for (int i = 0; i < count; i++) {
			// Biased toward the hot inner disk.
			double r = inner + (outer - inner) * Math.pow(random.nextDouble(), 1.5);
			double phi = random.nextDouble() * 2.0 * Math.PI;
			double height = (random.nextDouble() * 2.0 - 1.0) * DISK_HALF_THICKNESS;
			particles.addParticle(new DiskParticle(world, hole, sprites, r, phi, height, 80 + random.nextInt(60), scale));
		}
	}

	/**
	 * Gas along two logarithmic arms beyond the disk: spiralling in to a black hole (faster while it feeds, faster
	 * still for a fast-spinning hole), or streaming out of a white hole.
	 */
	private static void spawnVortex(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget, boolean feeding) {
		double outer = VORTEX_OUTER_RS * rs;
		double inner = DISK_OUTER_RS * rs;
		double spinUp = 1.0 + (feeding ? 0.6 : 0.0) + 0.5 * hole.spin();
		double inflow = hole.white() ? -0.008 : feeding ? 0.012 : 0.006;
		float scale = (float) Math.min(Math.max(0.08 * rs, 0.14), 3.5);
		int count = count(VORTEX_PARTICLES_PER_TICK * budget, random);
		for (int i = 0; i < count; i++) {
			double r = hole.white() ? inner * (1.0 + 0.3 * random.nextDouble()) : inner + (outer - inner) * random.nextDouble();
			// Two arms, phi = arm * pi - k ln(r) (trailing in the prograde sense), with some scatter.
			double phi = (i & 1) * Math.PI - 2.2 * Math.log(r / inner) + random.nextGaussian() * 0.25;
			double height = random.nextGaussian() * 0.04;
			particles.addParticle(new VortexParticle(world, hole, sprites, r, phi, height, spinUp, inflow,
					90 + random.nextInt(60), scale));
		}
	}

	/** Sparks whirling round the photon sphere on randomly tilted planes and spiralling down onto the horizon. */
	private static void spawnHorizonSwirl(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget) {
		float scale = (float) Math.min(Math.max(0.05 * rs, 0.09), 2.0);
		int count = count(HORIZON_SWIRL_PER_TICK * budget, random);
		for (int i = 0; i < count; i++) {
			// Mostly near the equator (the disk feeds it), some on steep orbits.
			Vec3d normal = new Vec3d(random.nextGaussian() * 0.45, 1.0, random.nextGaussian() * 0.45).normalize();
			if (random.nextInt(4) == 0) {
				normal = randomDirection(random);
			}
			double r = Relativity.photonSphere(rs) * (1.0 + 0.5 * random.nextDouble());
			particles.addParticle(new HorizonSwirlParticle(world, hole, sprites, normal, r, random.nextDouble() * 2.0 * Math.PI,
					40 + random.nextInt(30), scale));
		}
	}

	private static void spawnJets(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			double jetLength, Sprite[] sprites, Random random, double budget, float[] innerColor, float[] outerColor) {
		double spin = hole.spin();
		// Jet power pulses as the field lines wound up by the horizon reconnect.
		double pulse = 0.55 + 0.9 * (0.5 + 0.5 * Math.sin(world.getTime() * JET_PULSE_RATE));
		double power = spin * spin * budget;
		if (power <= 0.0) {
			return;
		}
		double horizon = hole.horizonRadius();
		double base = 1.2 * horizon;
		float scale = (float) Math.min(Math.max(0.05 * rs, 0.1), 2.5) * (float) (0.85 + 0.3 * pulse);
		for (double sign = -1.0; sign <= 1.0; sign += 2.0) {
			int count = count(JET_PARTICLES_PER_TICK * power * pulse, random);
			for (int i = 0; i < count; i++) {
				int maxAge = 30 + random.nextInt(20);
				double rho0 = random.nextDouble() * 0.25 * horizon;
				double phi = random.nextDouble() * 2.0 * Math.PI;
				double spread = JET_SPREAD * (0.5 + random.nextDouble());
				JetParticle particle = new JetParticle(world, hole, sprites, sign, base, rho0, phi,
						jetLength / maxAge, spread, JET_TWIST * spin, maxAge, scale);
				float[] color = random.nextBoolean() ? innerColor : outerColor;
				particle.setColor(color[0], color[1], color[2]);
				particles.addParticle(particle);
			}
			if (random.nextDouble() < JET_PLASMOIDS_PER_TICK * power) {
				spawnPlasmoid(particles, world, hole, sign, base, jetLength, horizon, sprites, random, spin, scale);
			}
			spawnJetShocks(particles, world, hole, sign, base, jetLength, sprites, random, power, scale, outerColor);
		}
	}

	/** A bright energy knot: a tight, slower cluster of plasma riding out along the jet together. */
	private static void spawnPlasmoid(ParticleManager particles, ClientWorld world, BlackHoleData hole, double sign,
			double base, double jetLength, double horizon, Sprite[] sprites, Random random, double spin, float scale) {
		int maxAge = 60;
		double phi = random.nextDouble() * 2.0 * Math.PI;
		for (int i = 0; i < 8; i++) {
			JetParticle knot = new JetParticle(world, hole, sprites, sign, base, 0.1 * horizon * random.nextDouble(),
					phi + random.nextGaussian() * 0.3, jetLength / maxAge, JET_SPREAD * 0.3, JET_TWIST * spin, maxAge,
					scale * (2.0f + random.nextFloat()));
			knot.setColor(1.0f, 1.0f, 1.0f);
			particles.addParticle(knot);
		}
	}

	/** Lightning-like sparks along the jet, flame at its root, and the occasional ring-shaped shock knot. */
	private static void spawnJetShocks(ParticleManager particles, ClientWorld world, BlackHoleData hole, double sign,
			double base, double jetLength, Sprite[] sprites, Random random, double power, float scale, float[] ringColor) {
		Vec3d center = hole.pos();
		int sparks = count(JET_SPARKS_PER_TICK * power, random);
		for (int i = 0; i < sparks; i++) {
			double d = base + random.nextDouble() * jetLength;
			double rho = d * JET_SPREAD * random.nextDouble();
			double phi = random.nextDouble() * 2.0 * Math.PI;
			// Electric sparks keep a quarter of the velocity they are given.
			particles.addParticle(ParticleTypes.ELECTRIC_SPARK, center.x + rho * Math.sin(phi), center.y + sign * d,
					center.z + rho * Math.cos(phi), random.nextGaussian() * 0.8, sign * 2.0, random.nextGaussian() * 0.8);
		}
		int flames = count(JET_FLAMES_PER_TICK * power, random);
		for (int i = 0; i < flames; i++) {
			double d = base + random.nextDouble() * Math.min(jetLength * 0.25, 12.0);
			particles.addParticle(random.nextInt(3) == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
					center.x + random.nextGaussian() * 0.2, center.y + sign * d, center.z + random.nextGaussian() * 0.2,
					random.nextGaussian() * 0.02, sign * 0.15, random.nextGaussian() * 0.02);
		}
		if (random.nextDouble() < JET_KNOTS_PER_TICK * power) {
			double d = base + (0.2 + 0.6 * random.nextDouble()) * jetLength;
			double y = center.y + sign * d;
			double ringSpeed = 0.12 + 0.002 * d;
			for (int i = 0; i < KNOT_PARTICLES; i++) {
				double phi = 2.0 * Math.PI * i / KNOT_PARTICLES;
				particles.addParticle(FreeGlowParticle.of(world, sprites, new Vec3d(center.x, y, center.z),
						new Vec3d(Math.sin(phi) * ringSpeed, sign * 0.3, Math.cos(phi) * ringSpeed), 0.95, 18,
						scale * 1.4f, ringColor));
			}
			particles.addParticle(ParticleTypes.ELECTRIC_SPARK, center.x, y, center.z, 0.0, sign * 1.2, 0.0);
		}
	}

	/**
	 * Air rushing in from every direction, and crumbs of the surrounding terrain (drawn with their own block
	 * texture and colour) torn loose and falling into the horizon.
	 */
	private static void spawnInflow(MinecraftClient client, ParticleManager particles, ClientWorld world, BlackHoleData hole,
			double rs, Sprite[] sprites, Random random, double budget) {
		Vec3d center = hole.pos();
		double influence = hole.influenceRadius();
		int air = count(AIR_PER_TICK * budget, random);
		for (int i = 0; i < air; i++) {
			double r = Math.min((4.0 + 14.0 * random.nextDouble()) * rs + 6.0, influence);
			Vec3d dir = randomDirection(random);
			// A slight prograde swirl, so the streams curve in rather than falling straight.
			Vec3d swirl = new Vec3d(dir.z, 0.0, -dir.x).multiply(0.06);
			InflowParticle wisp = new InflowParticle(world, hole, sprites, center.add(dir.multiply(r)), swirl,
					80, 0.08f + 0.08f * random.nextFloat(), false);
			wisp.setColor(AIR_COLOR[0], AIR_COLOR[1], AIR_COLOR[2]);
			particles.addParticle(wisp);
		}
		if (hole.stable()) {
			return; // a stable hole tears nothing loose
		}
		int crumbs = count(CRUMBS_PER_TICK * budget, random);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int i = 0; i < crumbs; i++) {
			for (int attempt = 0; attempt < CRUMB_SAMPLES; attempt++) {
				double r = Math.min((2.5 + 8.0 * random.nextDouble()) * rs + 3.0, influence);
				Vec3d at = center.add(randomDirection(random).multiply(r));
				pos.set(at.x, at.y, at.z);
				BlockState state = world.getBlockState(pos);
				if (state.isAir()) {
					continue;
				}
				Sprite sprite = client.getBlockRenderManager().getModels().getModelParticleSprite(state);
				InflowParticle crumb = new InflowParticle(world, hole, new Sprite[] {sprite}, at, Vec3d.ZERO, 100,
						0.08f + 0.1f * random.nextFloat(), true);
				int tint = client.getBlockColors().getColor(state, world, pos, 0);
				if (tint != -1) {
					crumb.setColor(((tint >> 16) & 0xFF) / 255.0f, ((tint >> 8) & 0xFF) / 255.0f, (tint & 0xFF) / 255.0f);
				}
				particles.addParticle(crumb);
				break;
			}
		}
	}

	/**
	 * Shreds stripped off every mob or player stretched by the tides, streaming into the hole, with bright sparks
	 * crackling off them where they tear.
	 */
	private static void spawnTearing(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget, Camera camera) {
		Vec3d center = hole.pos();
		double influence = hole.influenceRadius();
		for (Entity entity : world.getEntities()) {
			if (!(entity instanceof LivingEntity) || !entity.isAlive()
					|| (entity == camera.getFocusedEntity() && !camera.isThirdPerson())) {
				continue;
			}
			Box box = entity.getBoundingBox();
			Vec3d body = box.getCenter();
			double r = body.distanceTo(center);
			if (r > influence || body.distanceTo(camera.getPos()) > TEAR_RANGE) {
				continue;
			}
			double stretch = Relativity.tidalStretch(rs, r, entity.getHeight());
			if (stretch < TEAR_STRETCH) {
				continue;
			}
			int count = count(Math.min((stretch - TEAR_STRETCH) * TEARS_PER_STRETCH, MAX_TEARS_PER_TICK) * budget, random);
			Vec3d toHole = center.subtract(body).normalize();
			for (int i = 0; i < count; i++) {
				Vec3d at = new Vec3d(MathHelper.lerp(random.nextDouble(), box.minX, box.maxX),
						MathHelper.lerp(random.nextDouble(), box.minY, box.maxY),
						MathHelper.lerp(random.nextDouble(), box.minZ, box.maxZ));
				Vec3d velocity = entity.getVelocity().add(toHole.multiply(0.1 + 0.2 * random.nextDouble()));
				InflowParticle shred = new InflowParticle(world, hole, sprites, at, velocity, 50,
						0.07f + 0.08f * random.nextFloat(), false);
				float[] color = random.nextBoolean() ? TEAR_RED : TEAR_DARK;
				shred.setColor(color[0], color[1], color[2]);
				particles.addParticle(shred);
				// The tear itself: sparks spat along the stretch, toward and away from the hole.
				if (random.nextInt(2) == 0) {
					double along = random.nextBoolean() ? 1.0 : -0.4;
					Vec3d sparkVelocity = toHole.multiply(along * (0.4 + 0.4 * random.nextDouble()))
							.add(randomDirection(random).multiply(0.08));
					particles.addParticle(FreeGlowParticle.of(world, sprites, at, sparkVelocity, 0.88, 8 + random.nextInt(6),
							0.06f + 0.05f * random.nextFloat(), TEAR_SPARK));
				}
				if (random.nextInt(4) == 0) {
					particles.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
							toHole.x * 0.6, toHole.y * 0.6, toHole.z * 0.6);
				}
			}
		}
	}

	private static void spawnHawking(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget) {
		double horizon = hole.horizonRadius();
		double rate = Math.min(HAWKING_PER_TICK / hole.mass(), MAX_HAWKING_PER_TICK) * budget;
		float scale = (float) Math.min(Math.max(0.035 * rs, 0.08), 1.5);
		int count = count(rate, random);
		for (int i = 0; i < count; i++) {
			Vec3d dir = randomDirection(random);
			double r = horizon * (1.05 + 0.4 * random.nextDouble());
			double speed = (0.04 + 0.08 * random.nextDouble()) * Math.max(rs, 1.0);
			HawkingParticle particle = new HawkingParticle(world, hole, sprites, dir, r, speed, 25 + random.nextInt(25), scale);
			float[] color = random.nextBoolean() ? HAWKING_BLUE : HAWKING_VIOLET;
			particle.setColor(color[0], color[1], color[2]);
			particles.addParticle(particle);
		}
	}

	/** Green-violet sparkles drifting along the edge of the radiation zone, so it can be seen and avoided. */
	private static void spawnRadiationAura(ParticleManager particles, ClientWorld world, BlackHoleData hole,
			Sprite[] sprites, Random random, double budget) {
		double radius = HawkingRadiation.zoneRadius(hole);
		int count = count(RADIATION_SPARKLES_PER_TICK * budget, random);
		for (int i = 0; i < count; i++) {
			Vec3d dir = randomDirection(random);
			Vec3d drift = new Vec3d(dir.z, 0.0, -dir.x).multiply(0.03);
			particles.addParticle(FreeGlowParticle.of(world, sprites, hole.pos().add(dir.multiply(radius * (0.92 + 0.08 * random.nextDouble()))),
					drift, 1.0, 30, 0.18f, random.nextBoolean() ? RADIATION_GREEN : HAWKING_VIOLET));
		}
	}

	/**
	 * A cosmic tendril: a curving strand of dark violet light reaching from the corrupted ground (or a point of the
	 * zone) into the hole, each mote drifting along the strand toward the singularity.
	 */
	private static void spawnTendrils(ParticleManager particles, ClientWorld world, BlackHoleData hole, double radius,
			Sprite[] sprites, Random random, double budget) {
		if (random.nextDouble() >= TENDRIL_CHANCE * budget) {
			return;
		}
		Vec3d center = hole.pos();
		Vec3d start = null;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int attempt = 0; attempt < 6 && start == null; attempt++) {
			Vec3d at = center.add(randomDirection(random).multiply(radius * (0.5 + 0.5 * random.nextDouble())));
			pos.set(at.x, at.y, at.z);
			if (!world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir()) {
				start = new Vec3d(at.x, pos.getY() + 1.05, at.z);
			}
		}
		if (start == null) {
			return;
		}
		Vec3d end = center;
		Vec3d mid = start.lerp(end, 0.5);
		Vec3d axis = end.subtract(start);
		double length = axis.length();
		if (length < 1.0) {
			return;
		}
		// Bow the strand sideways and upward.
		Vec3d side = axis.crossProduct(randomDirection(random)).normalize().multiply(length * (0.15 + 0.2 * random.nextDouble()));
		Vec3d control = mid.add(side).add(0.0, length * 0.15, 0.0);
		float scale = (float) Math.min(0.08 + 0.004 * length, 0.3);
		for (int i = 0; i < TENDRIL_POINTS; i++) {
			double t = (double) i / (TENDRIL_POINTS - 1);
			Vec3d point = bezier(start, control, end, t);
			Vec3d next = bezier(start, control, end, Math.min(t + 0.08, 1.0));
			Vec3d drift = next.subtract(point).multiply(0.12);
			float[] color = (i & 1) == 0 ? TENDRIL_DARK : TENDRIL_LIGHT;
			particles.addParticle(FreeGlowParticle.of(world, sprites, point, drift, 0.97, 16 + i, scale, color));
		}
	}

	private static Vec3d bezier(Vec3d a, Vec3d control, Vec3d b, double t) {
		double u = 1.0 - t;
		return a.multiply(u * u).add(control.multiply(2.0 * u * t)).add(b.multiply(t * t));
	}

	/**
	 * A white hole's core: a blazing point with white and cyan outbursts, ringed by two tilted ejection rings
	 * precessing slowly and spinning in opposite senses.
	 */
	private static void spawnWhiteCore(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget, long time) {
		Vec3d pos = hole.pos();
		particles.addParticle(FreeGlowParticle.of(world, sprites, pos, Vec3d.ZERO, 1.0, 3, (float) Math.min(0.6 * rs + 0.4, 6.0), WHITE));
		int bursts = count(8.0 * budget, random);
		for (int i = 0; i < bursts; i++) {
			Vec3d v = randomDirection(random).multiply((0.25 + 0.35 * random.nextDouble()) * Math.max(rs / 2.8, 0.5));
			particles.addParticle(FreeGlowParticle.of(world, sprites, pos, v, 0.96, 18 + random.nextInt(14),
					0.2f + 0.15f * random.nextFloat(), random.nextBoolean() ? WHITE : CYAN));
		}
		double ringRadius = Math.max(RING_RADIUS_RS * rs, 1.2);
		double precession = time * 0.01;
		for (int ring = 0; ring < 2; ring++) {
			double tilt = ring == 0 ? RING_TILT : -RING_TILT;
			Vec3d normal = new Vec3d(Math.sin(tilt) * Math.cos(precession), Math.cos(tilt), Math.sin(tilt) * Math.sin(precession));
			Vec3d u = normal.crossProduct(new Vec3d(0.0, 0.0, 1.0)).normalize();
			Vec3d w = normal.crossProduct(u);
			double spin = (ring == 0 ? 0.18 : -0.18) * time;
			for (int j = 0; j < 3; j++) {
				double theta = spin + j * 2.0 * Math.PI / 3.0;
				Vec3d radial = u.multiply(Math.cos(theta)).add(w.multiply(Math.sin(theta)));
				Vec3d tangent = w.multiply(Math.cos(theta)).subtract(u.multiply(Math.sin(theta)));
				Vec3d velocity = tangent.multiply(ring == 0 ? 0.08 : -0.08).add(radial.multiply(0.03));
				particles.addParticle(FreeGlowParticle.of(world, sprites, pos.add(radial.multiply(ringRadius)), velocity,
						0.98, 10, 0.28f, j == 0 ? WHITE : GOLD));
			}
		}
	}

	/** Gas blown out of a white hole in every direction, decelerating as it climbs away (the mirror of infall). */
	private static void spawnOutflow(ParticleManager particles, ClientWorld world, BlackHoleData hole, double rs,
			Sprite[] sprites, Random random, double budget) {
		Vec3d center = hole.pos();
		int count = count(WHITE_OUTFLOW_PER_TICK * budget, random);
		for (int i = 0; i < count; i++) {
			Vec3d dir = randomDirection(random);
			Vec3d start = center.add(dir.multiply(hole.horizonRadius() * (1.05 + 0.3 * random.nextDouble())));
			Vec3d swirl = new Vec3d(-dir.z, 0.0, dir.x).multiply(0.05);
			InflowParticle wisp = new InflowParticle(world, hole, sprites, start, dir.multiply(0.15).add(swirl), 70,
					0.08f + 0.08f * random.nextFloat(), false);
			float[] color = random.nextBoolean() ? CYAN : WHITE;
			wisp.setColor(color[0], color[1], color[2]);
			particles.addParticle(wisp);
		}
	}

	/**
	 * A white hole's repulsion shockwave made visible: a shell and a ring of light flying out, several jets of light
	 * thrown out along random directions, crackling sparks and a flash.
	 */
	private static void spawnEjectionPulse(ParticleManager particles, ClientWorld world, Vec3d pos, double rs, Sprite[] sprites,
			Random random, double budget, double strength) {
		double speed = (0.55 + 0.25 * Math.min(rs / 2.8, 3.0)) * strength;
		burst(particles, world, sprites, pos, (int) (140 * budget * strength), speed, 0.95, 26, 0.32f, WHITE, CYAN);
		ring(particles, world, sprites, pos, (int) (90 * budget * strength), 1.3 * speed, 30, 0.42f, GOLD);
		int jets = 5 + random.nextInt(3);
		for (int j = 0; j < jets; j++) {
			Vec3d axis = randomDirection(random);
			int count = (int) (18 * budget * strength);
			for (int i = 0; i < count; i++) {
				Vec3d v = axis.add(randomDirection(random).multiply(0.12)).normalize().multiply(speed * (1.0 + 1.2 * random.nextDouble()));
				particles.addParticle(FreeGlowParticle.of(world, sprites, pos, v, 0.94, 22 + random.nextInt(10),
						0.24f + 0.12f * random.nextFloat(), random.nextBoolean() ? WHITE : GOLD));
			}
		}
		for (int i = 0; i < (int) (16 * budget * strength); i++) {
			Vec3d v = randomDirection(random).multiply(1.6 * speed);
			particles.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, v.x, v.y, v.z);
		}
		particles.addParticle(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0);
	}

	/** A spherical gravitational-wave front with a brighter equatorial ring, expanding from {@code pos}. */
	public static void spawnShockwave(MinecraftClient client, Vec3d pos, float strength) {
		ClientWorld world = client.world;
		Sprite[] sprites = world == null ? null : sprites(client);
		if (sprites == null) {
			return;
		}
		double budget = Math.max(budget(client), 0.25);
		ParticleManager particles = client.particleManager;
		double speed = 0.9 * strength;
		burst(particles, world, sprites, pos, (int) (240 * budget), speed, 0.97, 36, 0.35f, SHOCKWAVE_COLOR, SHOCKWAVE_COLOR);
		ring(particles, world, sprites, pos, (int) (80 * budget), 1.4 * speed, 40, 0.5f, SHOCKWAVE_RING_COLOR);
		particles.addParticle(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0);
	}

	/** Two holes merging: two gravitational-wave fronts, one after the other, and the ringdown's double flash. */
	public static void spawnMerger(MinecraftClient client, Vec3d pos, float strength) {
		spawnShockwave(client, pos, strength);
		spawnShockwave(client, pos, 0.6f * strength);
		ClientWorld world = client.world;
		Sprite[] sprites = world == null ? null : sprites(client);
		if (sprites == null) {
			return;
		}
		double budget = Math.max(budget(client), 0.25);
		// A vertical ring crossing the equatorial one: the wave is not spherical.
		int count = (int) (90 * budget);
		for (int i = 0; i < count; i++) {
			double phi = 2.0 * Math.PI * i / count;
			Vec3d v = new Vec3d(Math.cos(phi), Math.sin(phi), 0.0).multiply(1.1 * strength);
			client.particleManager.addParticle(FreeGlowParticle.of(world, sprites, pos, v, 0.97, 40, 0.45f, SHOCKWAVE_RING_COLOR));
		}
	}

	/** A white hole's last ejection: a blinding flash and a storm of white, gold and cyan light. */
	public static void spawnDetonation(MinecraftClient client, Vec3d pos, float strength) {
		ClientWorld world = client.world;
		Sprite[] sprites = world == null ? null : sprites(client);
		if (sprites == null) {
			return;
		}
		double budget = Math.max(budget(client), 0.3);
		spawnEjectionPulse(client.particleManager, world, pos, 2.8, sprites, world.random, budget, 2.2 * Math.max(strength, 0.5));
		for (int i = 0; i < 3; i++) {
			client.particleManager.addParticle(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * A Hawking flare: an evaporating hole crossing a critical mass releases a green-violet burst, two crossed rings
	 * and a flash, brighter the lighter (hotter) the hole.
	 */
	public static void spawnHawkingFlare(MinecraftClient client, Vec3d pos, float strength) {
		ClientWorld world = client.world;
		Sprite[] sprites = world == null ? null : sprites(client);
		if (sprites == null) {
			return;
		}
		double budget = Math.max(budget(client), 0.25);
		double speed = 0.6 + 0.15 * Math.min(strength, 10.0);
		ParticleManager particles = client.particleManager;
		burst(particles, world, sprites, pos, (int) (300 * budget), speed, 0.96, 40, 0.4f, RADIATION_GREEN, HAWKING_VIOLET);
		int ringCount = (int) (90 * budget);
		ring(particles, world, sprites, pos, ringCount, 1.3 * speed, 44, 0.5f, RADIATION_GREEN);
		// The second ring, vertical, crossing the first.
		for (int i = 0; i < ringCount; i++) {
			double phi = 2.0 * Math.PI * i / ringCount;
			Vec3d v = new Vec3d(Math.cos(phi), Math.sin(phi), 0.0).multiply(1.3 * speed);
			particles.addParticle(FreeGlowParticle.of(world, sprites, pos, v, 0.97, 44, 0.5f, HAWKING_VIOLET));
		}
		for (int i = 0; i < 2; i++) {
			particles.addParticle(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0);
		}
	}

	/** The last of an evaporating hole's mass radiating away: a white-violet flash and burst. */
	public static void spawnEvaporation(MinecraftClient client, Vec3d pos) {
		ClientWorld world = client.world;
		Sprite[] sprites = world == null ? null : sprites(client);
		if (sprites == null) {
			return;
		}
		Random random = world.random;
		int count = (int) (320 * Math.max(budget(client), 0.25));
		for (int i = 0; i < count; i++) {
			Vec3d v = randomDirection(random).multiply(0.4 + random.nextDouble());
			float[] color = random.nextInt(3) == 0 ? HAWKING_VIOLET : new float[] {1.0f, 1.0f, 1.0f};
			client.particleManager.addParticle(FreeGlowParticle.of(world, sprites, pos, v, 0.94, 30 + random.nextInt(20), 0.4f, color));
		}
		for (int i = 0; i < 3; i++) {
			client.particleManager.addParticle(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * The crossing of the Singularity Dimension: streaks of coloured light rushing past and swirling around the line
	 * of sight, faster and denser as the five seconds run out, with rings of light flying through the tunnel and a
	 * white flash just before ejection.
	 */
	private static void spawnSingularityStreaks(MinecraftClient client, ClientWorld world, Sprite[] sprites) {
		if (client.player == null) {
			return;
		}
		if (transitWorld != world) {
			transitWorld = world;
			transitStart = world.getTime();
		}
		double progress = MathHelper.clamp((world.getTime() - transitStart) / TRANSIT_TICKS, 0.0, 1.0);
		Random random = world.random;
		Vec3d eye = client.player.getEyePos();
		Vec3d look = client.player.getRotationVec(1.0f);
		Vec3d side = look.crossProduct(new Vec3d(0.0, 1.0, 0.0));
		side = side.lengthSquared() < 1.0e-6 ? new Vec3d(1.0, 0.0, 0.0) : side.normalize();
		Vec3d up = side.crossProduct(look).normalize();
		float hue = (world.getTime() % 200L) / 200.0f;
		double speed = 1.4 + 2.2 * progress;
		double budget = Math.max(budget(client), 0.3);
		int count = count((8.0 + 14.0 * progress) * budget, random);
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * 2.0 * Math.PI;
			double radius = 2.0 + 6.0 * random.nextDouble();
			Vec3d offset = side.multiply(Math.cos(angle) * radius).add(up.multiply(Math.sin(angle) * radius));
			Vec3d start = eye.add(look.multiply(30.0 + 10.0 * random.nextDouble())).add(offset);
			// Rushing toward the viewer and swirling around the line of sight.
			Vec3d velocity = look.multiply(-speed).add(side.multiply(-Math.sin(angle) * 0.25)).add(up.multiply(Math.cos(angle) * 0.25));
			int rgb = MathHelper.hsvToRgb((hue + 0.25f * random.nextFloat()) % 1.0f, 0.6f, 1.0f);
			float[] color = {((rgb >> 16) & 0xFF) / 255.0f, ((rgb >> 8) & 0xFF) / 255.0f, (rgb & 0xFF) / 255.0f};
			// A short train of particles along the velocity reads as a streak.
			Vec3d step = velocity.normalize().multiply(-0.7);
			for (int k = 0; k < 3; k++) {
				client.particleManager.addParticle(FreeGlowParticle.of(world, sprites, start.add(step.multiply(k)), velocity,
						1.0, 28, 0.32f - 0.08f * k, color));
			}
		}
		if (world.getTime() % 8L == 0L) {
			int ring = (int) (28 * budget);
			Vec3d center = eye.add(look.multiply(40.0));
			int rgb = MathHelper.hsvToRgb((hue + 0.5f) % 1.0f, 0.4f, 1.0f);
			float[] color = {((rgb >> 16) & 0xFF) / 255.0f, ((rgb >> 8) & 0xFF) / 255.0f, (rgb & 0xFF) / 255.0f};
			for (int i = 0; i < ring; i++) {
				double angle = 2.0 * Math.PI * i / ring;
				Vec3d at = center.add(side.multiply(Math.cos(angle) * 5.0)).add(up.multiply(Math.sin(angle) * 5.0));
				client.particleManager.addParticle(FreeGlowParticle.of(world, sprites, at, look.multiply(-speed), 1.0, 30, 0.4f, color));
			}
		}
		if (world.getTime() - transitStart == (long) TRANSIT_TICKS - 4L) {
			Vec3d at = eye.add(look.multiply(2.0));
			client.particleManager.addParticle(ParticleTypes.FLASH, at.x, at.y, at.z, 0.0, 0.0, 0.0);
		}
	}

	/** A sphere of particles flying out from {@code pos}, alternating two colours. */
	private static void burst(ParticleManager particles, ClientWorld world, Sprite[] sprites, Vec3d pos, int count,
			double speed, double damping, int maxAge, float scale, float[] colorA, float[] colorB) {
		// Fibonacci sphere: evenly spread directions.
		double golden = Math.PI * (3.0 - Math.sqrt(5.0));
		for (int i = 0; i < count; i++) {
			double y = 1.0 - 2.0 * (i + 0.5) / count;
			double rho = Math.sqrt(1.0 - y * y);
			double phi = golden * i;
			Vec3d v = new Vec3d(rho * Math.cos(phi), y, rho * Math.sin(phi)).multiply(speed);
			particles.addParticle(FreeGlowParticle.of(world, sprites, pos, v, damping, maxAge, scale, (i & 1) == 0 ? colorA : colorB));
		}
	}

	/** A horizontal ring of particles flying out from {@code pos}. */
	private static void ring(ParticleManager particles, ClientWorld world, Sprite[] sprites, Vec3d pos, int count,
			double speed, int maxAge, float scale, float[] color) {
		for (int i = 0; i < count; i++) {
			double phi = 2.0 * Math.PI * i / count;
			particles.addParticle(FreeGlowParticle.of(world, sprites, pos, new Vec3d(Math.cos(phi), 0.0, Math.sin(phi)).multiply(speed),
					0.97, maxAge, scale, color));
		}
	}

	private static Vec3d randomDirection(Random random) {
		return WormholeParticles.randomDirection(random);
	}

	/** Integer part of {@code rate}, plus one with the probability of its fractional part. */
	private static int count(double rate, Random random) {
		return WormholeParticles.count(rate, random);
	}
}
