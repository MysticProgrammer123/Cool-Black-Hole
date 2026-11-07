package com.prof.blackhole.client.mixin;

import com.prof.blackhole.client.gui.ControlPanelClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sneak-using a hole opens the control panel on it instead of using the held item. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@Shadow
	private int itemUseCooldown;

	@Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
	private void profs_black_hole$openControlPanel(CallbackInfo ci) {
		if (ControlPanelClient.onUse((MinecraftClient) (Object) this)) {
			this.itemUseCooldown = 4;
			ci.cancel();
		}
	}
}
