package com.prof.blackhole.client.render;

import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.data.BlackHoleData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The light-emitting blocks (torches, lanterns, glowstone, ...) closest to the hole, for the lensing shader to trace
 * as point sources: light from them that bends around the hole forms their Einstein rings and secondary images, even
 * for lights off screen or behind the camera, which the screen-space lookup of the scene cannot see.
 * <p>
 * Rescanned once a second. Chunk sections whose palette holds no bright block are skipped without being iterated,
 * so a scan costs little more than the handful of sections that actually contain lights.
 */
public final class LightSources {
	public static final int MAX_LIGHTS = 8;
	/** Dimmer blocks (redstone torches, glow lichen, ...) are not traced. */
	private static final int MIN_LUMINANCE = 10;
	private static final double MAX_SCAN_RADIUS = 48.0;
	private static final int SCAN_INTERVAL = 20;
	/** Radius of a light's glow in the lensed image, blocks. */
	private static final float GLOW_RADIUS = 0.45f;
	/** HDR intensity of a luminance-15 light. */
	private static final float INTENSITY = 2.2f;

	/** A traced light: absolute block-centre position, linear HDR colour and glow radius. */
	public record Light(double x, double y, double z, float red, float green, float blue, float radius) {
	}

	/** Holes scanned per second: the ones nearest the camera. */
	private static final int MAX_HOLES = 4;

	private static volatile Map<String, List<Light>> lights = Map.of();

	private LightSources() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientWorld world = client.world;
			if (world == null || ClientBlackHoleState.isEmpty()) {
				lights = Map.of();
			} else if (world.getTime() % SCAN_INTERVAL == 0L) {
				Vec3d camera = client.gameRenderer.getCamera().getPos();
				List<BlackHoleData> holes = new ArrayList<>(ClientBlackHoleState.all());
				holes.sort(Comparator.comparingDouble(hole -> hole.pos().squaredDistanceTo(camera)));
				Map<String, List<Light>> found = new HashMap<>();
				for (BlackHoleData hole : holes.subList(0, Math.min(holes.size(), MAX_HOLES))) {
					found.put(hole.id(), scan(world, hole));
				}
				lights = Map.copyOf(found);
			}
		});
	}

	/** The lights traced around the hole (none for holes too far from the camera to be scanned). */
	public static List<Light> get(String holeId) {
		return lights.getOrDefault(holeId, List.of());
	}

	private static List<Light> scan(ClientWorld world, BlackHoleData hole) {
		Vec3d center = hole.pos();
		double radius = Math.min(hole.influenceRadius(), MAX_SCAN_RADIUS);
		double radiusSq = radius * radius;
		record Candidate(double distanceSq, Light light) {
		}
		List<Candidate> found = new ArrayList<>();

		int minCx = ChunkSectionPos.getSectionCoord(center.x - radius);
		int maxCx = ChunkSectionPos.getSectionCoord(center.x + radius);
		int minCz = ChunkSectionPos.getSectionCoord(center.z - radius);
		int maxCz = ChunkSectionPos.getSectionCoord(center.z + radius);
		int minSy = Math.max(ChunkSectionPos.getSectionCoord(center.y - radius), world.getBottomSectionCoord());
		int maxSy = Math.min(ChunkSectionPos.getSectionCoord(center.y + radius), world.getTopSectionCoord() - 1);
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				WorldChunk chunk = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				ChunkSection[] sections = chunk.getSectionArray();
				for (int sy = minSy; sy <= maxSy; sy++) {
					ChunkSection section = sections[world.sectionCoordToIndex(sy)];
					if (section.isEmpty() || !section.getBlockStateContainer().hasAny(s -> s.getLuminance() >= MIN_LUMINANCE)) {
						continue;
					}
					for (int i = 0; i < 4096; i++) {
						int lx = i & 15;
						int lz = (i >> 4) & 15;
						int ly = i >> 8;
						BlockState state = section.getBlockState(lx, ly, lz);
						int luminance = state.getLuminance();
						if (luminance < MIN_LUMINANCE) {
							continue;
						}
						double x = (cx << 4) + lx + 0.5;
						double y = (sy << 4) + ly + 0.5;
						double z = (cz << 4) + lz + 0.5;
						double dSq = center.squaredDistanceTo(x, y, z);
						if (dSq <= radiusSq) {
							found.add(new Candidate(dSq, light(state, luminance, x, y, z)));
						}
					}
				}
			}
		}
		// The closest lights are bent the most.
		return found.stream().sorted(Comparator.comparingDouble(Candidate::distanceSq)).limit(MAX_LIGHTS)
			.map(Candidate::light).toList();
	}

	private static Light light(BlockState state, int luminance, double x, double y, double z) {
		String path = Registries.BLOCK.getId(state.getBlock()).getPath();
		float[] color;
		if (path.contains("soul")) {
			color = new float[] {0.35f, 0.75f, 1.0f};
		} else if (path.contains("sea_lantern") || path.contains("beacon") || path.contains("end_rod") || path.contains("froglight")
			|| path.contains("conduit")) {
			color = new float[] {0.85f, 0.93f, 1.0f};
		} else if (path.contains("redstone")) {
			color = new float[] {1.0f, 0.25f, 0.1f};
		} else {
			color = new float[] {1.0f, 0.68f, 0.32f}; // flame
		}
		float intensity = INTENSITY * luminance / 15.0f;
		return new Light(x, y, z, color[0] * intensity, color[1] * intensity, color[2] * intensity, GLOW_RADIUS);
	}
}
