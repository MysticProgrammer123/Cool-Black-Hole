package com.prof.blackhole;

import com.prof.blackhole.block.BlackHoleBlocks;
import com.prof.blackhole.command.BlackHoleCommand;
import com.prof.blackhole.command.ControlPanelServer;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.entity.BlackHoleStatusEffects;
import com.prof.blackhole.entity.GravityHandler;
import com.prof.blackhole.item.BlackHoleArmorMaterials;
import com.prof.blackhole.item.BlackHoleItems;
import com.prof.blackhole.network.BlackHoleEventPayload;
import com.prof.blackhole.network.BlackHoleSyncPayload;
import com.prof.blackhole.network.PanelPayloads;
import com.prof.blackhole.network.WormholeSyncPayload;
import com.prof.blackhole.sound.BlackHoleSounds;
import com.prof.blackhole.world.BlackHoleEvolution;
import com.prof.blackhole.world.BlackHoleGameRules;
import com.prof.blackhole.world.BlockDisruption;
import com.prof.blackhole.world.HawkingRadiation;
import com.prof.blackhole.world.ServerFallbackVisuals;
import com.prof.blackhole.world.SingularityCollapses;
import com.prof.blackhole.world.SpacetimeCorruption;
import com.prof.blackhole.world.TimeStasis;
import com.prof.blackhole.wormhole.SingularityTransit;
import com.prof.blackhole.wormhole.WhiteHoleManager;
import com.prof.blackhole.wormhole.WormholeSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Black Hole. The mod id stays {@value #MOD_ID} (the mod's original name), so worlds, items and commands from
 * earlier versions keep working.
 */
public final class ProfsBlackHole implements ModInitializer {
	public static final String MOD_ID = "profs_black_hole";
	public static final String NAME = "Black Hole";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	@Override
	public void onInitialize() {
		BlackHoleBlocks.register();
		BlackHoleArmorMaterials.register();
		BlackHoleItems.register();
		BlackHoleSounds.register();
		BlackHoleStatusEffects.register();
		BlackHoleGameRules.register();

		PayloadTypeRegistry.playS2C().register(BlackHoleSyncPayload.ID, BlackHoleSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(BlackHoleEventPayload.ID, BlackHoleEventPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(WormholeSyncPayload.ID, WormholeSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PanelPayloads.Snapshot.ID, PanelPayloads.Snapshot.CODEC);
		PayloadTypeRegistry.playC2S().register(PanelPayloads.Request.ID, PanelPayloads.Request.CODEC);
		PayloadTypeRegistry.playC2S().register(PanelPayloads.Action.ID, PanelPayloads.Action.CODEC);
		BlackHoleManager.register();
		ControlPanelServer.register();
		CommandRegistrationCallback.EVENT.register(BlackHoleCommand::register);

		GravityHandler.register();
		BlockDisruption.register();
		BlackHoleEvolution.register();
		HawkingRadiation.register();
		TimeStasis.register();
		SpacetimeCorruption.register();
		SingularityCollapses.register();
		WhiteHoleManager.register();
		WormholeSync.register();
		SingularityTransit.register();
		ServerFallbackVisuals.register();
		LOGGER.info("{} initialized", NAME);
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
