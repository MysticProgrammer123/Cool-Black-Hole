package com.prof.blackhole.block;

import com.mojang.serialization.MapCodec;
import com.prof.blackhole.data.BlackHoleState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * Gravity Stabilizer: pins spacetime flat around itself. Within {@value #RADIUS} blocks no hole tears blocks loose,
 * drains fluids, empties containers or corrupts the ground; entities are still pulled. The stabilizer itself is
 * immune ({@code #profs_black_hole:black_hole_immune}).
 * <p>
 * Each dimension remembers its stabilizers ({@link BlackHoleState#stabilizers}), so a hole finds them without
 * searching the world; entries whose block is gone (moved by a command, or a chunk regenerated) are dropped by
 * {@link #validate}.
 */
public final class GravityStabilizerBlock extends Block {
	public static final MapCodec<GravityStabilizerBlock> CODEC = createCodec(GravityStabilizerBlock::new);
	/** Radius of the protected sphere, blocks. */
	public static final double RADIUS = 16.0;
	private static final DustParticleEffect SHIELD = new DustParticleEffect(new Vector3f(0.35F, 0.9F, 1.0F), 1.2F);

	public GravityStabilizerBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends Block> getCodec() {
		return CODEC;
	}

	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		super.onBlockAdded(state, world, pos, oldState, notify);
		if (world instanceof ServerWorld serverWorld) {
			BlackHoleState.get(serverWorld).addStabilizer(pos);
		}
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world instanceof ServerWorld serverWorld) {
			BlackHoleState.get(serverWorld).removeStabilizer(pos);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	/** Sparks over the block, and now and then a cyan glint on the edge of the protected sphere. */
	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		Vec3d center = Vec3d.ofCenter(pos);
		double angle = random.nextDouble() * 2.0 * Math.PI;
		world.addParticle(ParticleTypes.ELECTRIC_SPARK, center.x + Math.cos(angle) * 0.7, center.y + 0.7, center.z + Math.sin(angle) * 0.7,
			0.0, 0.05, 0.0);
		for (int i = 0; i < 2; i++) {
			double y = random.nextDouble() * 2.0 - 1.0;
			double phi = random.nextDouble() * 2.0 * Math.PI;
			double rho = Math.sqrt(1.0 - y * y);
			world.addParticle(SHIELD, center.x + RADIUS * rho * Math.cos(phi), center.y + RADIUS * y, center.z + RADIUS * rho * Math.sin(phi),
				0.0, 0.0, 0.0);
		}
	}

	/** Stabilizers whose protected sphere reaches into the sphere of {@code radius} around {@code center}. */
	public static List<BlockPos> near(ServerWorld world, Vec3d center, double radius) {
		List<BlockPos> result = new ArrayList<>();
		double reach = radius + RADIUS + 1.0;
		double reachSq = reach * reach;
		for (long packed : BlackHoleState.get(world).stabilizers()) {
			BlockPos pos = BlockPos.fromLong(packed);
			if (Vec3d.ofCenter(pos).squaredDistanceTo(center) < reachSq) {
				result.add(pos);
			}
		}
		return result;
	}

	/** Whether any of {@code stabilizers} protects the block at {@code pos}. */
	public static boolean protects(List<BlockPos> stabilizers, BlockPos pos) {
		for (BlockPos stabilizer : stabilizers) {
			if (stabilizer.getSquaredDistance(pos) <= RADIUS * RADIUS) {
				return true;
			}
		}
		return false;
	}

	/** Forgets stabilizers in loaded chunks whose block is no longer there. */
	public static void validate(ServerWorld world) {
		BlackHoleState state = BlackHoleState.get(world);
		List<BlockPos> stale = new ArrayList<>();
		for (long packed : state.stabilizers()) {
			BlockPos pos = BlockPos.fromLong(packed);
			if (world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
				&& !world.getBlockState(pos).isOf(BlackHoleBlocks.GRAVITY_STABILIZER)) {
				stale.add(pos);
			}
		}
		stale.forEach(state::removeStabilizer);
	}
}
