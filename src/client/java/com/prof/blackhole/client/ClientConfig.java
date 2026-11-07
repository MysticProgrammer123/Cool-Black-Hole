package com.prof.blackhole.client;

import com.prof.blackhole.ProfsBlackHole;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.MathHelper;

/**
 * Per-player display settings, changed from the control panel and kept in {@code config/black_hole-client.properties}:
 * whether the lensing shader runs (off: the holes are drawn as plain geometry, which costs almost nothing), how many
 * holes it lenses at once, and the density of the particle effects.
 */
public final class ClientConfig {
	private static final String FILE = "black_hole-client.properties";
	private static final int MAX_PASSES = 8;

	private static boolean lensing = true;
	private static int maxLensedHoles = 4;
	private static double particles = 1.0;

	private ClientConfig() {
	}

	public static boolean lensing() {
		return lensing;
	}

	public static void setLensing(boolean value) {
		lensing = value;
		save();
	}

	public static int maxLensedHoles() {
		return maxLensedHoles;
	}

	public static void setMaxLensedHoles(int value) {
		maxLensedHoles = MathHelper.clamp(value, 1, MAX_PASSES);
		save();
	}

	/** Multiplier on the particle effects, on top of the video settings' Particles option. */
	public static double particles() {
		return particles;
	}

	public static void setParticles(double value) {
		particles = MathHelper.clamp(value, 0.0, 2.0);
		save();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE);
	}

	public static void load() {
		Path path = path();
		if (!Files.isRegularFile(path)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			properties.load(in);
			lensing = Boolean.parseBoolean(properties.getProperty("lensing", "true"));
			maxLensedHoles = MathHelper.clamp(Integer.parseInt(properties.getProperty("maxLensedHoles", "4").trim()), 1, MAX_PASSES);
			particles = MathHelper.clamp(Double.parseDouble(properties.getProperty("particles", "1.0").trim()), 0.0, 2.0);
		} catch (IOException | RuntimeException e) {
			ProfsBlackHole.LOGGER.warn("Could not read {}; using defaults", path, e);
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty("lensing", Boolean.toString(lensing));
		properties.setProperty("maxLensedHoles", Integer.toString(maxLensedHoles));
		properties.setProperty("particles", Double.toString(particles));
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (OutputStream out = Files.newOutputStream(path)) {
				properties.store(out, "Black Hole client settings");
			}
		} catch (IOException e) {
			ProfsBlackHole.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
