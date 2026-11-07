package com.prof.blackhole.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * Event Horizon Waste: grey, lifeless ground left where a long-standing black hole has warped the soil
 * ({@link com.prof.blackhole.world.SpacetimeCorruption}). Nothing grows on it (it is not dirt), and faint violet
 * motes drift up from it, drawn toward the hole.
 */
public final class EventHorizonWasteBlock extends Block {
	public static final MapCodec<EventHorizonWasteBlock> CODEC = createCodec(EventHorizonWasteBlock::new);
	private static final DustColorTransitionParticleEffect MOTE =
		new DustColorTransitionParticleEffect(new Vector3f(0.45F, 0.3F, 0.65F), new Vector3f(0.12F, 0.1F, 0.16F), 0.7F);

	public EventHorizonWasteBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends Block> getCodec() {
		return CODEC;
	}

	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (!world.getBlockState(pos.up()).isAir() || random.nextInt(3) != 0) {
			return;
		}
		double x = pos.getX() + random.nextDouble();
		double z = pos.getZ() + random.nextDouble();
		if (random.nextInt(4) == 0) {
			world.addParticle(ParticleTypes.REVERSE_PORTAL, x, pos.getY() + 1.05, z, 0.0, 0.04, 0.0);
		} else {
			world.addParticle(MOTE, x, pos.getY() + 1.05, z, 0.0, 0.02, 0.0);
		}
	}
}
