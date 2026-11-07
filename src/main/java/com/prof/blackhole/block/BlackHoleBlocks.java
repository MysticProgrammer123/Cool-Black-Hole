package com.prof.blackhole.block;

import com.prof.blackhole.ProfsBlackHole;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/** The mod's blocks; their items are registered by {@link com.prof.blackhole.item.BlackHoleItems}. */
public final class BlackHoleBlocks {
	public static final Block GRAVITY_STABILIZER = register("gravity_stabilizer", new GravityStabilizerBlock(
		AbstractBlock.Settings.create()
			.mapColor(MapColor.CYAN)
			.instrument(NoteBlockInstrument.IRON_XYLOPHONE)
			.strength(5.0F, 1200.0F)
			.requiresTool()
			.sounds(BlockSoundGroup.NETHERITE)
			.luminance(state -> 9)));

	public static final Block EVENT_HORIZON_WASTE = register("event_horizon_waste", new EventHorizonWasteBlock(
		AbstractBlock.Settings.create()
			.mapColor(MapColor.GRAY)
			.strength(0.6F)
			.sounds(BlockSoundGroup.ROOTED_DIRT)));

	private BlackHoleBlocks() {
	}

	/** Loads the class, registering the blocks. */
	public static void register() {
	}

	private static Block register(String name, Block block) {
		return Registry.register(Registries.BLOCK, ProfsBlackHole.id(name), block);
	}
}
