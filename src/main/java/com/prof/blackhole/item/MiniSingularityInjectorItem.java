package com.prof.blackhole.item;

import com.prof.blackhole.world.SingularityCollapses;
import java.util.List;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Used in the air: cycles its {@link InjectorMode} (Implosion, Evaporation, Stationary). Used on a block: consumed,
 * and a hole of the selected mode collapses at the block face that was clicked, after
 * {@link SingularityCollapses#COLLAPSE_TICKS} ticks. It fails (and is kept) where the dimension is full or the
 * spot is taken by another hole or collapse.
 */
public final class MiniSingularityInjectorItem extends Item {
	public MiniSingularityInjectorItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		InjectorMode mode = InjectorMode.of(stack).next();
		if (!world.isClient) {
			mode.applyTo(stack);
			user.sendMessage(Text.translatable("item.profs_black_hole.mini_singularity_injector.mode_set", mode.displayName()), true);
			world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN,
				SoundCategory.PLAYERS, 0.6F, 0.8F + 0.3F * mode.ordinal());
		}
		return TypedActionResult.success(stack, world.isClient);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		if (!(context.getWorld() instanceof ServerWorld world)) {
			return ActionResult.SUCCESS;
		}
		PlayerEntity player = context.getPlayer();
		// The centre of the air block in front of the clicked face.
		BlockPos at = context.getBlockPos().offset(context.getSide());
		Vec3d pos = Vec3d.ofCenter(at);
		var blocked = SingularityCollapses.blocked(world, pos);
		if (blocked.isPresent()) {
			if (player != null) {
				player.sendMessage(blocked.get().copy().formatted(Formatting.RED), true);
			}
			return ActionResult.FAIL;
		}
		InjectorMode mode = InjectorMode.of(context.getStack());
		SingularityCollapses.start(world, mode.hole(pos), mode.displayName());
		context.getStack().decrementUnlessCreative(1, player);
		return ActionResult.CONSUME;
	}

	@Override
	public Text getName(ItemStack stack) {
		return Text.translatable(getTranslationKey(stack) + ".named", InjectorMode.of(stack).displayName());
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		InjectorMode mode = InjectorMode.of(stack);
		tooltip.add(Text.translatable("item.profs_black_hole.mini_singularity_injector.tooltip.mode", mode.displayName())
			.formatted(Formatting.GRAY));
		tooltip.add(mode.description().copy().formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.translatable("item.profs_black_hole.mini_singularity_injector.tooltip.0").formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("item.profs_black_hole.mini_singularity_injector.tooltip.1",
			SingularityCollapses.COLLAPSE_TICKS / 20).formatted(Formatting.DARK_PURPLE));
	}
}
