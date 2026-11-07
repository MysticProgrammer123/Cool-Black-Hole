package com.prof.blackhole.physics;

/**
 * Physical constants and closed-form general-relativity results used by both the
 * gameplay (server) and rendering (client) code.
 *
 * <p>Conventions: 1 block = 1 metre, masses are given in Jupiter masses, and all
 * radii are in blocks. Geometric mass {@code M = r_s / 2} and the Kerr parameter
 * {@code a = a* M} are lengths, so every formula below is written in G = c = 1 form
 * and only converted to game time where an explicit time unit is involved.
 *
 * <p>Gameplay dynamics would be invisible at the true speed of light, so they use a
 * scaled "game speed of light" {@link #C_GAME} in blocks/tick. The geometry (r_s,
 * horizon, ISCO, photon sphere) is always the real one; only time scales change:
 * {@code GM_game = C_GAME^2 * r_s / 2} in blocks^3/tick^2.
 */
public final class Relativity {
	/** Newtonian gravitational constant, m^3 kg^-1 s^-2. */
	public static final double G = 6.67430e-11;
	/** Speed of light in vacuum, m/s. */
	public static final double C = 299_792_458.0;
	/** Reduced Planck constant, J s. */
	public static final double HBAR = 1.054571817e-34;
	/** Boltzmann constant, J/K. */
	public static final double K_B = 1.380649e-23;
	/** One Jupiter mass, kg. */
	public static final double JUPITER_MASS_KG = 1.89813e27;

	/**
	 * Game speed of light in blocks/tick. Chosen so that a 1 M_J hole (r_s ~ 2.819 blocks)
	 * pulls with ~vanilla gravity (0.08 blocks/tick^2) at 6 r_s: C^2 = 0.08 * 72 * r_s ~ 16.2.
	 */
	public static final double C_GAME = 4.0;

	/** Vanilla entity gravity, blocks/tick^2; reference scale for tidal stretching. */
	private static final double VANILLA_GRAVITY = 0.08;
	private static final double MAX_TIDAL_STRETCH = 6.0;
	private static final double MAX_INFLUENCE_RADIUS = 192.0;

	/** r_s per Jupiter mass in metres (= blocks), 2 G M_J / c^2 ~ 2.819. */
	private static final double RS_PER_JUPITER_MASS = 2.0 * G * JUPITER_MASS_KG / (C * C);

	private Relativity() {
	}

	/** Schwarzschild radius r_s = 2GM/c^2 in blocks. */
	public static double schwarzschildRadius(double massJupiter) {
		return RS_PER_JUPITER_MASS * massJupiter;
	}

	/** Outer (event) horizon of a Kerr hole, r_+ = M (1 + sqrt(1 - a*^2)), in blocks. */
	public static double outerHorizon(double rs, double spin) {
		double a = clampSpin(spin);
		return 0.5 * rs * (1.0 + Math.sqrt(1.0 - a * a));
	}

	/**
	 * Prograde innermost stable circular orbit (Bardeen, Press &amp; Teukolsky 1972), in blocks.
	 * Equals 3 r_s for a* = 0 and approaches r_s / 2 as a* approaches 1.
	 */
	public static double iscoRadius(double rs, double spin) {
		double a = clampSpin(spin);
		double m = 0.5 * rs;
		double z1 = 1.0 + Math.cbrt(1.0 - a * a) * (Math.cbrt(1.0 + a) + Math.cbrt(1.0 - a));
		double z2 = Math.sqrt(3.0 * a * a + z1 * z1);
		return m * (3.0 + z2 - Math.sqrt((3.0 - z1) * (3.0 + z1 + 2.0 * z2)));
	}

	/** Schwarzschild photon sphere, r = 3M = 1.5 r_s, in blocks. */
	public static double photonSphere(double rs) {
		return 1.5 * rs;
	}

	/** Hawking temperature T = hbar c^3 / (8 pi G M k_B), in Kelvin. */
	public static double hawkingTemperature(double massJupiter) {
		double massKg = massJupiter * JUPITER_MASS_KG;
		if (massKg <= 0.0) {
			return 0.0;
		}
		return HBAR * C * C * C / (8.0 * Math.PI * G * massKg * K_B);
	}

	/** Newtonian-limit acceleration GM_game / r^2 in blocks/tick^2. */
	public static double gravitationalAcceleration(double rs, double r) {
		if (r <= 0.0) {
			return 0.0;
		}
		return C_GAME * C_GAME * rs / (2.0 * r * r);
	}

	/** Differential (tidal) acceleration across a body of the given length: 2 GM_game L / r^3, blocks/tick^2. */
	public static double tidalAcceleration(double rs, double r, double length) {
		if (r <= 0.0) {
			return 0.0;
		}
		return C_GAME * C_GAME * rs * length / (r * r * r);
	}

	/**
	 * Frame-dragging angular velocity of the locally non-rotating frame in the equatorial
	 * plane (theta = pi/2), in rad/tick:
	 * <pre>omega = 2 M a r c / ((r^2 + a^2)^2 - a^2 Delta),  Delta = r^2 - 2 M r + a^2</pre>
	 * with c replaced by {@link #C_GAME}. Positive means prograde (counter-clockwise about +Y).
	 */
	public static double frameDraggingOmega(double rs, double spin, double r) {
		double aStar = clampSpin(spin);
		if (r <= 0.0 || aStar <= 0.0) {
			return 0.0;
		}
		double m = 0.5 * rs;
		double a = aStar * m;
		double r2a2 = r * r + a * a;
		double delta = r * r - 2.0 * m * r + a * a;
		double denominator = r2a2 * r2a2 - a * a * delta;
		if (denominator <= 0.0) {
			return 0.0;
		}
		return 2.0 * m * a * r * C_GAME / denominator;
	}

	/**
	 * Dimensionless volume-preserving stretch factor for spaghettification visuals:
	 * {@code 1 + tidalAcceleration / 0.08}, clamped to [1, 6]. Returns 1 for r &lt;= 0.
	 */
	public static double tidalStretch(double rs, double r, double length) {
		if (r <= 0.0) {
			return 1.0;
		}
		double s = 1.0 + tidalAcceleration(rs, r, length) / VANILLA_GRAVITY;
		return Math.min(Math.max(s, 1.0), MAX_TIDAL_STRETCH);
	}

	/**
	 * Gravitational time dilation of a static observer, {@code dtau/dt = sqrt(1 - r_s / r)} (Schwarzschild),
	 * relative to an observer at infinity. Also the gravitational redshift factor {@code 1 / (1 + z)} of light
	 * emitted at r. Returns 0 at and inside r_s.
	 */
	public static double timeDilationFactor(double rs, double r) {
		if (r <= rs) {
			return 0.0;
		}
		return Math.sqrt(1.0 - rs / r);
	}

	/**
	 * Clock rate of a static observer near a white hole relative to one far away. A white hole's exterior is the
	 * time-reversed black hole; this mod models its repulsion as a negative-mass (M &lt; 0) Schwarzschild field, for
	 * which {@code dtau/dt = sqrt(1 + r_s / r)}: time runs fast near it, the mirror image of black-hole time
	 * dilation. Also the blueshift factor of light falling from far away to r.
	 */
	public static double timeAccelerationFactor(double rs, double r) {
		if (r <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}
		return Math.sqrt(1.0 + rs / r);
	}

	/**
	 * Radius of a white hole's blinding core, blocks: the surface nothing can enter, standing where a black hole's
	 * horizon would be (r = 2M = r_s). The lensing shader draws the same surface.
	 */
	public static double whiteHoleCoreRadius(double rs) {
		return rs;
	}

	/** Radius in blocks within which gravity, tidal effects and rendering are evaluated. */
	public static double influenceRadius(double rs) {
		return Math.min(32.0 * rs + 16.0, MAX_INFLUENCE_RADIUS);
	}

	private static double clampSpin(double spin) {
		return Math.min(Math.max(spin, 0.0), 0.9999);
	}
}
