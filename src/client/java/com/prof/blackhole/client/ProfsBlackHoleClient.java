package com.prof.blackhole.client;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.client.gui.ControlPanelClient;
import com.prof.blackhole.client.gui.HolePanelScreen;
import com.prof.blackhole.client.particle.BlackHoleParticles;
import com.prof.blackhole.client.render.BlackHoleRenderer;
import com.prof.blackhole.client.render.GogglesHud;
import com.prof.blackhole.client.render.LightSources;
import com.prof.blackhole.client.render.SingularityInjectorRenderer;
import com.prof.blackhole.client.render.SpatialRifts;
import com.prof.blackhole.item.BlackHoleItems;
import com.prof.blackhole.network.BlackHoleEventPayload;
import com.prof.blackhole.network.BlackHoleSyncPayload;
import com.prof.blackhole.network.PanelPayloads;
import com.prof.blackhole.network.WormholeSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;

public final class ProfsBlackHoleClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();

		// Fabric API 1.21.1 invokes play payload handlers on the render thread.
		ClientPlayNetworking.registerGlobalReceiver(BlackHoleSyncPayload.ID,
				(payload, context) -> ClientBlackHoleState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(BlackHoleEventPayload.ID, (payload, context) -> {
			switch (payload.kind()) {
				case SHOCKWAVE -> BlackHoleParticles.spawnShockwave(context.client(), payload.pos(), payload.strength());
				case FEEDING -> ClientBlackHoleState.setFeeding(payload.id(), payload.strength() > 0.5f);
				case EVAPORATED -> BlackHoleParticles.spawnEvaporation(context.client(), payload.pos());
				case HAWKING_FLARE -> BlackHoleParticles.spawnHawkingFlare(context.client(), payload.pos(), payload.strength());
				case DETONATION -> BlackHoleParticles.spawnDetonation(context.client(), payload.pos(), payload.strength());
				case MERGER -> BlackHoleParticles.spawnMerger(context.client(), payload.pos(), payload.strength());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(WormholeSyncPayload.ID,
				(payload, context) -> ClientWormholeState.set(payload.mouths()));
		ClientPlayNetworking.registerGlobalReceiver(PanelPayloads.Snapshot.ID,
				(payload, context) -> HolePanelScreen.accept(payload));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientBlackHoleState.clear();
			ClientWormholeState.clear();
		});

		// Compiles the lensing shader on every resource (re)load so GLSL errors surface in the log at startup.
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(BlackHoleRenderer.INSTANCE);

		BlackHoleParticles.register();
		SpatialRifts.register();
		LightSources.register();
		ClientGravity.register();
		ControlPanelClient.register();
		HudRenderCallback.EVENT.register(GogglesHud::render);
		BuiltinItemRendererRegistry.INSTANCE.register(BlackHoleItems.MINI_SINGULARITY_INJECTOR, new SingularityInjectorRenderer());

		ProfsBlackHole.LOGGER.info("{} client initialised", ProfsBlackHole.NAME);
	}
}
