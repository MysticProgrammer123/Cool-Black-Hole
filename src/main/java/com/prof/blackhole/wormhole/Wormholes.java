package com.prof.blackhole.wormhole;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.HoleRef;
import com.prof.blackhole.world.BlackHoleEvolution;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.feature.EndPlatformFeature;
import org.jetbrains.annotations.Nullable;

/**
 * Where a player who crosses an event horizon ends up. In order:
 * <ol>
 *     <li>through the wormhole to the hole's paired white hole, if it has one;</li>
 *     <li>through the ring singularity of a fast-spinning Kerr hole (a* &ge; {@value #TRAVERSABLE_SPIN}), when the
 *     player falls in near the spin axis and so misses the ring, which lies in the equatorial plane: into the next
 *     dimension (Overworld &rarr; Nether &rarr; End &rarr; Overworld);</li>
 *     <li>otherwise nowhere: the trip through the Singularity Dimension ends in destruction.</li>
 * </ol>
 */
public final class Wormholes {
	/** Minimum spin for a traversable ring singularity. */
	public static final double TRAVERSABLE_SPIN = 0.9;
	/** Entries within this angle of the spin axis avoid the ring, degrees. */
	public static final double POLAR_ENTRY_DEGREES = 30.0;
	private static final double POLAR_ENTRY_COS = Math.cos(Math.toRadians(POLAR_ENTRY_DEGREES));
	/** Columns searched around the target position for a place to stand, blocks. */
	private static final int SAFE_SEARCH_RADIUS = 8;
	private static final int FALLBACK_PLATFORM_Y = 70;

	private Wormholes() {
	}

	/** A survival player has crossed the horizon of {@code hole} in {@code world}. */
	public static void enterHorizon(ServerWorld world, BlackHoleData hole, ServerPlayerEntity player) {
		BlackHoleEvolution.consumeEntity(world, hole, player);
		SingularityTransit.begin(player, route(world, hole, player));
	}

	@Nullable
	private static SingularityTransit.Exit route(ServerWorld world, BlackHoleData hole, ServerPlayerEntity player) {
		if (hole.link().isPresent()) {
			HoleRef link = hole.link().get();
			return p -> {
				// Wherever the white hole is when the five seconds are up; gone by then, home to the respawn point.
				var whiteHole = BlackHoleManager.resolve(p.getServer(), link).filter(located -> located.hole().white());
				if (whiteHole.isEmpty()) {
					return null;
				}
				BlackHoleData exit = whiteHole.get().hole();
				// Above the blinding core, which nothing can enter, far enough out that its crushing tides are mild,
				// and flung clear by its repulsion.
				Vec3d at = exit.pos().add(0.0, 3.0 * exit.horizonRadius() + 2.0, 0.0);
				return new TeleportTarget(whiteHole.get().world(), at, new Vec3d(0.0, 0.6, 0.0), p.getYaw(), p.getPitch(),
					TeleportTarget.NO_OP);
			};
		}
		Vec3d rel = player.getBoundingBox().getCenter().subtract(hole.pos());
		double r = rel.length();
		boolean polar = r < 1.0e-3 || Math.abs(rel.y) >= POLAR_ENTRY_COS * r;
		if (hole.spin() >= TRAVERSABLE_SPIN && polar) {
			RegistryKey<World> from = world.getRegistryKey();
			Vec3d holePos = hole.pos();
			return p -> kerrExit(p, from, holePos);
		}
		return null;
	}

	@Nullable
	private static TeleportTarget kerrExit(ServerPlayerEntity player, RegistryKey<World> from, Vec3d holePos) {
		MinecraftServer server = player.getServer();
		RegistryKey<World> to = from == World.OVERWORLD ? World.NETHER : from == World.NETHER ? World.END : World.OVERWORLD;
		ServerWorld target = server.getWorld(to);
		ServerWorld source = server.getWorld(from);
		if (target == null) {
			return null;
		}
		if (to == World.END) {
			BlockPos spawn = ServerWorld.END_SPAWN_POS;
			EndPlatformFeature.generate(target, spawn.down(), true);
			return new TeleportTarget(target, spawn.toBottomCenterPos(), Vec3d.ZERO, Direction.WEST.asRotation(),
				player.getPitch(), TeleportTarget.NO_OP);
		}
		if (from == World.END || source == null) {
			return null; // out of the End: home to the respawn point, as through the exit portal
		}
		double scale = DimensionType.getCoordinateScaleFactor(source.getDimension(), target.getDimension());
		BlockPos column = target.getWorldBorder().clamp(holePos.x * scale, holePos.y, holePos.z * scale);
		BlockPos spot = findSafeSpot(target, column);
		return new TeleportTarget(target, spot.toBottomCenterPos(), Vec3d.ZERO, player.getYaw(), player.getPitch(),
			TeleportTarget.NO_OP);
	}

	/** A position with solid ground below and two blocks of air, near {@code column}; builds a platform if none. */
	private static BlockPos findSafeSpot(ServerWorld world, BlockPos column) {
		int bottom = world.getBottomY() + 1;
		int top = world.getBottomY() + world.getDimension().logicalHeight() - 2;
		if (!world.getDimension().hasCeiling()) {
			BlockPos surface = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
			if (surface.getY() > bottom && world.getFluidState(surface.down()).isEmpty()) {
				return surface;
			}
		}
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int ring = 0; ring <= SAFE_SEARCH_RADIUS; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					for (int y = top; y >= bottom; y--) {
						pos.set(column.getX() + dx, y, column.getZ() + dz);
						if (canStand(world, pos)) {
							return pos.toImmutable();
						}
					}
				}
			}
		}
		// Like a portal forcer: an obsidian landing pad with room to stand.
		BlockPos base = column.withY(MathHelper.clamp(FALLBACK_PLATFORM_Y, bottom + 1, top - 3));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				world.setBlockState(base.add(dx, -1, dz), Blocks.OBSIDIAN.getDefaultState());
				for (int dy = 0; dy < 3; dy++) {
					world.setBlockState(base.add(dx, dy, dz), Blocks.AIR.getDefaultState());
				}
			}
		}
		return base;
	}

	private static boolean canStand(ServerWorld world, BlockPos pos) {
		BlockPos below = pos.down();
		return world.getBlockState(pos).isAir()
			&& world.getBlockState(pos.up()).isAir()
			&& world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
			&& world.getFluidState(below).isEmpty();
	}
}
