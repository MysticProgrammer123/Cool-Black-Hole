package com.prof.blackhole.item;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.block.BlackHoleBlocks;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.Block;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Rarity;

public final class BlackHoleItems {
	public static final Item MINI_SINGULARITY_INJECTOR = register("mini_singularity_injector",
		new MiniSingularityInjectorItem(new Item.Settings().maxCount(16).rarity(Rarity.EPIC).fireproof()));

	/** Rare, dense crafting component left floating where a hole evaporated or a white hole detonated. */
	public static final Item SINGULARITY_CORE = register("singularity_core",
		new DescribedItem(new Item.Settings().maxCount(16).rarity(Rarity.EPIC).fireproof(), "singularity_core", 2));

	/** Worn on the head: gravitational field lines, horizon markers and a lensing grid ({@code GogglesVision}). */
	public static final Item EVENT_HORIZON_GOGGLES = register("event_horizon_goggles",
		new GogglesItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE).maxDamage(ArmorItem.Type.HELMET.getMaxDamage(15))));

	public static final Item GRAVITY_STABILIZER = registerBlock(BlackHoleBlocks.GRAVITY_STABILIZER, Rarity.RARE, "gravity_stabilizer", 2);
	public static final Item EVENT_HORIZON_WASTE = registerBlock(BlackHoleBlocks.EVENT_HORIZON_WASTE, Rarity.COMMON, "event_horizon_waste", 1);

	public static final ItemGroup GROUP = Registry.register(Registries.ITEM_GROUP, ProfsBlackHole.id("black_hole"),
		FabricItemGroup.builder()
			.icon(() -> new ItemStack(MINI_SINGULARITY_INJECTOR))
			.displayName(Text.translatable("itemGroup.profs_black_hole"))
			.entries((context, entries) -> {
				for (InjectorMode mode : InjectorMode.values()) {
					ItemStack injector = new ItemStack(MINI_SINGULARITY_INJECTOR);
					mode.applyTo(injector);
					entries.add(injector);
				}
				entries.add(SINGULARITY_CORE);
				entries.add(EVENT_HORIZON_GOGGLES);
				entries.add(GRAVITY_STABILIZER);
				entries.add(EVENT_HORIZON_WASTE);
			})
			.build());

	private BlackHoleItems() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries ->
			entries.addAfter(Items.RESPAWN_ANCHOR, MINI_SINGULARITY_INJECTOR, GRAVITY_STABILIZER));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries ->
			entries.addAfter(Items.NETHER_STAR, SINGULARITY_CORE));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries ->
			entries.addAfter(Items.TURTLE_HELMET, EVENT_HORIZON_GOGGLES));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries ->
			entries.addAfter(Items.ROOTED_DIRT, EVENT_HORIZON_WASTE));
	}

	private static Item register(String name, Item item) {
		return Registry.register(Registries.ITEM, ProfsBlackHole.id(name), item);
	}

	private static Item registerBlock(Block block, Rarity rarity, String tooltipKey, int tooltipLines) {
		return Registry.register(Registries.ITEM, Registries.BLOCK.getId(block),
			new DescribedBlockItem(block, new Item.Settings().rarity(rarity), tooltipKey, tooltipLines));
	}

	/** Grey tooltip lines {@code item.profs_black_hole.<key>.tooltip.<n>}. */
	static void describe(String key, int lines, List<Text> tooltip) {
		for (int i = 0; i < lines; i++) {
			tooltip.add(Text.translatable("item.profs_black_hole." + key + ".tooltip." + i).formatted(Formatting.GRAY));
		}
	}

	static final class DescribedItem extends Item {
		private final String key;
		private final int lines;

		DescribedItem(Settings settings, String key, int lines) {
			super(settings);
			this.key = key;
			this.lines = lines;
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			describe(key, lines, tooltip);
		}
	}

	static final class DescribedBlockItem extends BlockItem {
		private final String key;
		private final int lines;

		DescribedBlockItem(Block block, Settings settings, String key, int lines) {
			super(block, settings);
			this.key = key;
			this.lines = lines;
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			describe(key, lines, tooltip);
		}
	}
}
