package com.prof.blackhole.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.prof.blackhole.world.TimeStasis;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Time stasis near the hole ({@link TimeStasis}): random ticks and fire ticks are skipped inside 2 r_s. */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
	@WrapWithCondition(method = "tickChunk", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/block/BlockState;randomTick(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V"))
	private boolean profs_black_hole$freezeRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		return !TimeStasis.isFrozen(world, pos);
	}

	@WrapWithCondition(method = "tickChunk", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/fluid/FluidState;onRandomTick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V"))
	private boolean profs_black_hole$freezeFluidRandomTick(FluidState state, World world, BlockPos pos, Random random) {
		return !TimeStasis.isFrozen((ServerWorld) (Object) this, pos);
	}

	/** Fire spreads through scheduled ticks; a frozen fire is rescheduled rather than dropped, so it resumes later. */
	@WrapWithCondition(method = "tickBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/block/BlockState;scheduledTick(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V"))
	private boolean profs_black_hole$freezeFire(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (state.getBlock() instanceof AbstractFireBlock && TimeStasis.isFrozen(world, pos)) {
			world.scheduleBlockTick(pos, state.getBlock(), TimeStasis.FIRE_RETRY_TICKS);
			return false;
		}
		return true;
	}
}
