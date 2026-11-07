package com.prof.blackhole.data;

import com.prof.blackhole.physics.Relativity;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.math.Vec3d;

/**
 * Immutable state of one hole: a black hole, or (with {@code white}) its time-reversed counterpart, a white hole.
 *
 * @param id            unique id across all dimensions, e.g. {@code bh1} or {@code wh2} ({@link #isValidId})
 * @param white         true for a white hole: repulsive gravity, no horizon, a blinding core instead of a shadow
 * @param x             world X of the singularity (blocks)
 * @param y             world Y of the singularity (blocks)
 * @param z             world Z of the singularity (blocks)
 * @param mass          mass in Jupiter masses, [{@value #MIN_MASS}, {@value #MAX_MASS}]
 * @param spin          dimensionless Kerr spin a* = a/M, [{@value #MIN_SPIN}, {@value #MAX_SPIN}]; spin axis is world +Y
 * @param accretionDisk whether the accretion (for a white hole: ejection) disk is drawn
 * @param temperature   peak disk temperature in Kelvin, [{@value #MIN_TEMPERATURE}, {@value #MAX_TEMPERATURE}]
 * @param scale         event horizon scale: multiplies every length of the hole (r_s, horizon, reach of gravity)
 *                      without changing its mass, [{@value #MIN_SCALE}, {@value #MAX_SCALE}]
 * @param pullRadius    reach of the hole's gravity in blocks, or 0 for the automatic {@link Relativity#influenceRadius}
 * @param frozen        held still in time: no gravity, no feeding, no evolution, a motionless disk
 * @param stable        neither grows, evaporates nor devours blocks, but still pulls (or, if white, pushes)
 * @param bornTime      world time the hole formed, for its spacetime corruption; negative until first ticked
 * @param link          a black hole's wormhole: the white hole its matter and travellers come out of
 */
public record BlackHoleData(String id, boolean white, double x, double y, double z, double mass, double spin,
		boolean accretionDisk, double temperature, double scale, double pullRadius, boolean frozen, boolean stable,
		long bornTime, Optional<HoleRef> link) {
	public static final double MIN_MASS = 0.05;
	public static final double MAX_MASS = 50.0;
	public static final double MIN_SPIN = 0.0;
	public static final double MAX_SPIN = 0.99;
	public static final double MIN_TEMPERATURE = 1000;
	public static final double MAX_TEMPERATURE = 100000;
	public static final double MIN_SCALE = 0.25;
	public static final double MAX_SCALE = 4.0;
	/** An explicit pull radius is clamped to [{@value #MIN_PULL_RADIUS}, {@value #MAX_PULL_RADIUS}] blocks. */
	public static final double MIN_PULL_RADIUS = 2.0;
	public static final double MAX_PULL_RADIUS = 256.0;
	public static final int MAX_ID_LENGTH = 32;

	public static final double DEFAULT_MASS = 1.0;
	public static final double DEFAULT_SPIN = 0.6;
	public static final double DEFAULT_TEMPERATURE = 8000;
	/** White holes blaze hotter: their disk is blue-white. */
	public static final double DEFAULT_WHITE_TEMPERATURE = 20000;
	public static final boolean DEFAULT_ACCRETION_DISK = true;
	public static final double DEFAULT_SCALE = 1.0;

	private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1," + MAX_ID_LENGTH + "}");

	private static final String KEY_ID = "Id";
	private static final String KEY_WHITE = "White";
	private static final String KEY_X = "X";
	private static final String KEY_Y = "Y";
	private static final String KEY_Z = "Z";
	private static final String KEY_MASS = "Mass";
	private static final String KEY_SPIN = "Spin";
	private static final String KEY_ACCRETION_DISK = "AccretionDisk";
	private static final String KEY_TEMPERATURE = "Temperature";
	private static final String KEY_SCALE = "HorizonScale";
	private static final String KEY_PULL_RADIUS = "PullRadius";
	private static final String KEY_FROZEN = "Frozen";
	private static final String KEY_STABLE = "Stable";
	private static final String KEY_BORN = "BornTime";
	private static final String KEY_LINK = "Link";
	private static final String KEY_SCHWARZSCHILD_RADIUS = "SchwarzschildRadius";

	public static final PacketCodec<ByteBuf, BlackHoleData> PACKET_CODEC = PacketCodec.of(
		(data, buf) -> {
			PacketCodecs.STRING.encode(buf, data.id);
			buf.writeBoolean(data.white);
			buf.writeDouble(data.x);
			buf.writeDouble(data.y);
			buf.writeDouble(data.z);
			buf.writeDouble(data.mass);
			buf.writeDouble(data.spin);
			buf.writeBoolean(data.accretionDisk);
			buf.writeDouble(data.temperature);
			buf.writeDouble(data.scale);
			buf.writeDouble(data.pullRadius);
			buf.writeBoolean(data.frozen);
			buf.writeBoolean(data.stable);
			buf.writeLong(data.bornTime);
			buf.writeBoolean(data.link.isPresent());
			data.link.ifPresent(link -> HoleRef.PACKET_CODEC.encode(buf, link));
		},
		buf -> new BlackHoleData(
			PacketCodecs.STRING.decode(buf), buf.readBoolean(),
			buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readDouble(),
			buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
			buf.readBoolean() ? Optional.of(HoleRef.PACKET_CODEC.decode(buf)) : Optional.empty()
		)
	);

	public BlackHoleData {
		id = id == null ? "" : id;
		mass = clamp(mass, MIN_MASS, MAX_MASS, DEFAULT_MASS);
		spin = clamp(spin, MIN_SPIN, MAX_SPIN, DEFAULT_SPIN);
		temperature = clamp(temperature, MIN_TEMPERATURE, MAX_TEMPERATURE, white ? DEFAULT_WHITE_TEMPERATURE : DEFAULT_TEMPERATURE);
		scale = clamp(scale, MIN_SCALE, MAX_SCALE, DEFAULT_SCALE);
		pullRadius = !(pullRadius > 0.0) ? 0.0 : clamp(pullRadius, MIN_PULL_RADIUS, MAX_PULL_RADIUS, 0.0);
		// Only black holes lead anywhere.
		link = white || link == null ? Optional.empty() : link;
	}

	/** A new hole with default parameters (white holes start hotter). */
	public static BlackHoleData create(String id, boolean white, Vec3d pos, long bornTime) {
		return new BlackHoleData(id, white, pos.x, pos.y, pos.z, DEFAULT_MASS, DEFAULT_SPIN, DEFAULT_ACCRETION_DISK,
			white ? DEFAULT_WHITE_TEMPERATURE : DEFAULT_TEMPERATURE, DEFAULT_SCALE, 0.0, false, false, bornTime, Optional.empty());
	}

	/** Ids are 1-{@value #MAX_ID_LENGTH} letters, digits, {@code _ . -}. */
	public static boolean isValidId(String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	public Vec3d pos() {
		return new Vec3d(x, y, z);
	}

	/** Schwarzschild radius r_s = 2GM/c^2 in blocks (= metres), times the event horizon scale. */
	public double schwarzschildRadius() {
		return Relativity.schwarzschildRadius(mass) * scale;
	}

	/**
	 * Radius of the surface nothing passes: a black hole's outer event horizon, or a white hole's core, which
	 * nothing can enter (anything that gets there is thrown back out).
	 */
	public double horizonRadius() {
		double rs = schwarzschildRadius();
		return white ? Relativity.whiteHoleCoreRadius(rs) : Relativity.outerHorizon(rs, spin);
	}

	/** Reach of the hole's gravity, blocks: the explicit pull radius, or the automatic one. */
	public double influenceRadius() {
		return pullRadius > 0.0 ? pullRadius : Relativity.influenceRadius(schwarzschildRadius());
	}

	/** World time since the hole formed, ticks (0 until it has been ticked once). */
	public long age(long worldTime) {
		return bornTime < 0L ? 0L : Math.max(worldTime - bornTime, 0L);
	}

	public BlackHoleData withPos(double x, double y, double z) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withPos(Vec3d pos) {
		return withPos(pos.x, pos.y, pos.z);
	}

	public BlackHoleData withMass(double mass) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withSpin(double spin) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withAccretionDisk(boolean accretionDisk) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withTemperature(double temperature) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withScale(double scale) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withPullRadius(double pullRadius) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withFrozen(boolean frozen) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withStable(boolean stable) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withBornTime(long bornTime) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withLink(Optional<HoleRef> link) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public BlackHoleData withId(String id) {
		return new BlackHoleData(id, white, x, y, z, mass, spin, accretionDisk, temperature, scale, pullRadius, frozen, stable, bornTime, link);
	}

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString(KEY_ID, id);
		nbt.putBoolean(KEY_WHITE, white);
		nbt.putDouble(KEY_X, x);
		nbt.putDouble(KEY_Y, y);
		nbt.putDouble(KEY_Z, z);
		nbt.putDouble(KEY_MASS, mass);
		nbt.putDouble(KEY_SPIN, spin);
		nbt.putBoolean(KEY_ACCRETION_DISK, accretionDisk);
		nbt.putDouble(KEY_TEMPERATURE, temperature);
		nbt.putDouble(KEY_SCALE, scale);
		nbt.putDouble(KEY_PULL_RADIUS, pullRadius);
		nbt.putBoolean(KEY_FROZEN, frozen);
		nbt.putBoolean(KEY_STABLE, stable);
		nbt.putLong(KEY_BORN, bornTime);
		link.ifPresent(ref -> nbt.put(KEY_LINK, ref.toNbt()));
		// Informational only; always recomputed from Mass and HorizonScale on load.
		nbt.putDouble(KEY_SCHWARZSCHILD_RADIUS, schwarzschildRadius());
		return nbt;
	}

	/**
	 * Reads a hole; the id falls back to {@code fallbackId} (saves from before holes had ids), the birth time to
	 * "not yet ticked".
	 */
	public static BlackHoleData fromNbt(NbtCompound nbt, String fallbackId) {
		String id = nbt.getString(KEY_ID);
		boolean white = nbt.getBoolean(KEY_WHITE);
		Optional<HoleRef> link = nbt.contains(KEY_LINK, NbtElement.COMPOUND_TYPE)
			? Optional.ofNullable(HoleRef.fromNbt(nbt.getCompound(KEY_LINK))) : Optional.empty();
		return new BlackHoleData(
			isValidId(id) ? id : fallbackId,
			white,
			readDouble(nbt, KEY_X, 0.0),
			readDouble(nbt, KEY_Y, 0.0),
			readDouble(nbt, KEY_Z, 0.0),
			readDouble(nbt, KEY_MASS, DEFAULT_MASS),
			readDouble(nbt, KEY_SPIN, DEFAULT_SPIN),
			nbt.contains(KEY_ACCRETION_DISK, NbtElement.NUMBER_TYPE) ? nbt.getBoolean(KEY_ACCRETION_DISK) : DEFAULT_ACCRETION_DISK,
			readDouble(nbt, KEY_TEMPERATURE, white ? DEFAULT_WHITE_TEMPERATURE : DEFAULT_TEMPERATURE),
			readDouble(nbt, KEY_SCALE, DEFAULT_SCALE),
			readDouble(nbt, KEY_PULL_RADIUS, 0.0),
			nbt.getBoolean(KEY_FROZEN),
			nbt.getBoolean(KEY_STABLE),
			nbt.contains(KEY_BORN, NbtElement.NUMBER_TYPE) ? nbt.getLong(KEY_BORN) : -1L,
			link
		);
	}

	private static double readDouble(NbtCompound nbt, String key, double fallback) {
		if (!nbt.contains(key, NbtElement.NUMBER_TYPE)) {
			return fallback;
		}
		double value = nbt.getDouble(key);
		return Double.isFinite(value) ? value : fallback;
	}

	private static double clamp(double value, double min, double max, double fallback) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		return Math.min(Math.max(value, min), max);
	}
}
