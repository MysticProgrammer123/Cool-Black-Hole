package com.prof.blackhole.mixin;

import com.prof.blackhole.entity.GravityHandler;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Endermen caught in a black hole's pull cannot teleport out of it: the curved spacetime they would fold to get
 * away is the hole's. Every teleport (random, on damage, toward a target) fails while they are inside its influence.
 */
@Mixin(EndermanEntity.class)
public abstract class EndermanEntityMixin {
	@Inject(method = "teleportTo(DDD)Z", at = @At("HEAD"), cancellable = true)
	private void profs_black_hole$pinnedByHole(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		EndermanEntity self = (EndermanEntity) (Object) this;
		if (self.getWorld() instanceof ServerWorld world && GravityHandler.isInInfluence(world, self)) {
			cir.setReturnValue(false);
		}
	}
}
