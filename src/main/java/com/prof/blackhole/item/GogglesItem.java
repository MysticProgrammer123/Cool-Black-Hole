package com.prof.blackhole.item;

import java.util.List;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;

/**
 * Event Horizon Goggles: a light helmet whose lenses show spacetime itself. Worn, the client draws every hole's
 * gravitational field lines and its horizon, photon sphere, ISCO and danger radii, overlays a coordinate grid on
 * the lensed sky (so the warping can be read off directly), dims the disk's glare and shows a readout of the
 * nearest hole.
 */
public final class GogglesItem extends ArmorItem {
	public GogglesItem(Settings settings) {
		super(BlackHoleArmorMaterials.EVENT_HORIZON, Type.HELMET, settings);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		BlackHoleItems.describe("event_horizon_goggles", 2, tooltip);
	}
}
