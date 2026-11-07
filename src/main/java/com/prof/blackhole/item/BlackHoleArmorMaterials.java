package com.prof.blackhole.item;

import com.prof.blackhole.ProfsBlackHole;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvents;

public final class BlackHoleArmorMaterials {
	/**
	 * Event Horizon Goggles: light (iron-helmet protection), repaired with amethyst. The worn model's texture is
	 * {@code textures/models/armor/event_horizon_goggles_layer_1.png}.
	 */
	public static final RegistryEntry<ArmorMaterial> EVENT_HORIZON = Registry.registerReference(Registries.ARMOR_MATERIAL,
		ProfsBlackHole.id("event_horizon"), new ArmorMaterial(
			defense(),
			15,
			SoundEvents.ITEM_ARMOR_EQUIP_IRON,
			() -> Ingredient.ofItems(net.minecraft.item.Items.AMETHYST_SHARD),
			List.of(new ArmorMaterial.Layer(ProfsBlackHole.id("event_horizon_goggles"))),
			0.0F,
			0.0F));

	private BlackHoleArmorMaterials() {
	}

	private static EnumMap<ArmorItem.Type, Integer> defense() {
		EnumMap<ArmorItem.Type, Integer> map = new EnumMap<>(ArmorItem.Type.class);
		map.put(ArmorItem.Type.BOOTS, 1);
		map.put(ArmorItem.Type.LEGGINGS, 2);
		map.put(ArmorItem.Type.CHESTPLATE, 3);
		map.put(ArmorItem.Type.HELMET, 2);
		map.put(ArmorItem.Type.BODY, 3);
		return map;
	}

	/** Loads the class, registering the materials. */
	public static void register() {
	}
}
