package com.prof.blackhole.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.prof.blackhole.command.HoleControl.HoleException;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.BlackHoleManager.Located;
import com.prof.blackhole.physics.Relativity;
import com.prof.blackhole.wormhole.SingularityTransit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.DimensionArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * The {@code /blackhole} and {@code /whitehole} command trees (permission level 2), and their aliases {@code /bh}
 * and {@code /wh}. Both trees are built by the same code, so every subcommand exists for both kinds:
 * <pre>
 * spawn [x y z] | spawn &lt;id&gt; [x y z]
 * list
 * set mass|spin|accretion_disk|temperature|scale|stable &lt;value&gt; [id]
 * set position &lt;x y z&gt; [id]
 * set_pull_radius &lt;blocks, 0 = auto&gt; [id]            (/whitehole: also set_push_radius)
 * mass_add &lt;M_J, may be negative&gt; [id]
 * freeze [id] | unfreeze [id]
 * teleport [id]
 * inspect [id] | info [id]
 * destroy [id] | remove [id]
 * clear_all
 * link &lt;white hole id&gt; [id]   (/whitehole link &lt;black hole id&gt; [id])
 * unlink [id]
 * whitehole set &lt;x y z&gt; [dimension] | whitehole remove   (/blackhole only, as before)
 * </pre>
 * Without an id a command acts on the nearest hole of its kind in the current dimension; with one, on that hole in
 * whichever dimension it is.
 */
public final class BlackHoleCommand {
	private static final String KEY = HoleControl.KEY;
	private static final Predicate<ServerCommandSource> PERMISSION = source -> source.hasPermissionLevel(2);

	@FunctionalInterface
	private interface TargetCommand {
		int run(CommandContext<ServerCommandSource> ctx, Located target) throws CommandSyntaxException, HoleException;
	}

	private BlackHoleCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess,
			CommandManager.RegistrationEnvironment environment) {
		LiteralCommandNode<ServerCommandSource> blackhole = dispatcher.register(tree("blackhole", false));
		LiteralCommandNode<ServerCommandSource> whitehole = dispatcher.register(tree("whitehole", true));
		dispatcher.register(literal("bh").requires(PERMISSION).redirect(blackhole));
		dispatcher.register(literal("wh").requires(PERMISSION).redirect(whitehole));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> tree(String name, boolean white) {
		LiteralArgumentBuilder<ServerCommandSource> root = literal(name).requires(PERMISSION);

		root.then(literal("spawn")
			.executes(ctx -> spawn(ctx, null, ctx.getSource().getPosition(), white))
			.then(argument("pos", Vec3ArgumentType.vec3())
				.executes(ctx -> spawn(ctx, null, Vec3ArgumentType.getVec3(ctx, "pos"), white)))
			.then(argument("id", StringArgumentType.word())
				.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "id"), ctx.getSource().getPosition(), white))
				.then(argument("pos", Vec3ArgumentType.vec3())
					.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "id"), Vec3ArgumentType.getVec3(ctx, "pos"), white)))));

		root.then(literal("list").executes(ctx -> list(ctx.getSource(), white)));

		root.then(literal("set")
			.then(literal("mass").then(targeted(argument("value", DoubleArgumentType.doubleArg(BlackHoleData.MIN_MASS, BlackHoleData.MAX_MASS)),
				white, BlackHoleCommand::setMass)))
			.then(literal("spin").then(targeted(argument("value", DoubleArgumentType.doubleArg(BlackHoleData.MIN_SPIN, BlackHoleData.MAX_SPIN)),
				white, BlackHoleCommand::setSpin)))
			.then(literal("accretion_disk").then(targeted(argument("value", BoolArgumentType.bool()),
				white, BlackHoleCommand::setAccretionDisk)))
			.then(literal("temperature").then(targeted(argument("value",
				DoubleArgumentType.doubleArg(BlackHoleData.MIN_TEMPERATURE, BlackHoleData.MAX_TEMPERATURE)), white, BlackHoleCommand::setTemperature)))
			.then(literal("scale").then(targeted(argument("value", DoubleArgumentType.doubleArg(BlackHoleData.MIN_SCALE, BlackHoleData.MAX_SCALE)),
				white, BlackHoleCommand::setScale)))
			.then(literal("stable").then(targeted(argument("value", BoolArgumentType.bool()),
				white, BlackHoleCommand::setStable)))
			.then(literal("position").then(targeted(argument("pos", Vec3ArgumentType.vec3()),
				white, BlackHoleCommand::setPosition))));

		root.then(literal("set_pull_radius").then(targeted(argument("radius", DoubleArgumentType.doubleArg(0.0, BlackHoleData.MAX_PULL_RADIUS)),
			white, BlackHoleCommand::setPullRadius)));
		if (white) {
			root.then(literal("set_push_radius").then(targeted(argument("radius", DoubleArgumentType.doubleArg(0.0, BlackHoleData.MAX_PULL_RADIUS)),
				white, BlackHoleCommand::setPullRadius)));
		}
		root.then(literal("mass_add").then(targeted(argument("amount", DoubleArgumentType.doubleArg(-BlackHoleData.MAX_MASS, BlackHoleData.MAX_MASS)),
			white, BlackHoleCommand::massAdd)));
		root.then(targeted(literal("freeze"), white, (ctx, target) -> freeze(ctx, target, true)));
		root.then(targeted(literal("unfreeze"), white, (ctx, target) -> freeze(ctx, target, false)));
		root.then(targeted(literal("teleport"), white, BlackHoleCommand::teleport));
		root.then(targeted(literal("inspect"), white, BlackHoleCommand::inspect));
		root.then(targeted(literal("info"), white, BlackHoleCommand::inspect));
		root.then(targeted(literal("destroy"), white, BlackHoleCommand::destroy));
		root.then(targeted(literal("remove"), white, BlackHoleCommand::destroy));
		root.then(literal("clear_all").executes(ctx -> clearAll(ctx.getSource(), white)));

		if (white) {
			root.then(literal("link").then(targeted(argument("black_hole", StringArgumentType.word()).suggests(ids(false)),
				true, (ctx, whiteHole) -> link(ctx, require(ctx, StringArgumentType.getString(ctx, "black_hole"), false), whiteHole))));
			root.then(targeted(literal("unlink"), true, BlackHoleCommand::unlinkAll));
		} else {
			root.then(literal("link").then(targeted(argument("white_hole", StringArgumentType.word()).suggests(ids(true)),
				false, (ctx, blackHole) -> link(ctx, blackHole, require(ctx, StringArgumentType.getString(ctx, "white_hole"), true)))));
			root.then(targeted(literal("unlink"), false, BlackHoleCommand::unlink));
			// The single-hole commands of earlier versions: pair the nearest black hole with a white hole at a position.
			root.then(literal("whitehole")
				.then(literal("set")
					.then(argument("pos", Vec3ArgumentType.vec3())
						.executes(ctx -> legacySetWhiteHole(ctx, Vec3ArgumentType.getVec3(ctx, "pos"), ctx.getSource().getWorld()))
						.then(argument("dimension", DimensionArgumentType.dimension())
							.executes(ctx -> legacySetWhiteHole(ctx, Vec3ArgumentType.getVec3(ctx, "pos"),
								DimensionArgumentType.getDimensionArgument(ctx, "dimension"))))))
				.then(literal("remove").executes(BlackHoleCommand::legacyRemoveWhiteHole)));
		}
		return root;
	}

	/**
	 * Gives {@code node} two endings: without an id (the nearest hole of the kind) and with one ({@code id},
	 * suggested from every dimension).
	 */
	private static <T extends ArgumentBuilder<ServerCommandSource, T>> T targeted(T node, boolean white, TargetCommand command) {
		return node
			.executes(ctx -> run(ctx, white, false, command))
			.then(argument("id", StringArgumentType.word()).suggests(ids(white))
				.executes(ctx -> run(ctx, white, true, command)));
	}

	private static int run(CommandContext<ServerCommandSource> ctx, boolean white, boolean hasId, TargetCommand command)
			throws CommandSyntaxException {
		try {
			Located target = HoleControl.require(ctx.getSource().getWorld(), ctx.getSource().getPosition(),
				hasId ? StringArgumentType.getString(ctx, "id") : null, white);
			return command.run(ctx, target);
		} catch (HoleException e) {
			throw fail(e);
		}
	}

	private static Located require(CommandContext<ServerCommandSource> ctx, String id, boolean white) throws HoleException {
		return HoleControl.require(ctx.getSource().getWorld(), ctx.getSource().getPosition(), id, white);
	}

	private static SuggestionProvider<ServerCommandSource> ids(boolean white) {
		return (ctx, builder) -> {
			List<String> ids = new ArrayList<>();
			// The current dimension's holes first.
			ServerWorld here = ctx.getSource().getWorld();
			for (BlackHoleData hole : BlackHoleManager.holes(here, white)) {
				ids.add(hole.id());
			}
			for (ServerWorld world : ctx.getSource().getServer().getWorlds()) {
				if (world != here) {
					for (BlackHoleData hole : BlackHoleManager.holes(world, white)) {
						ids.add(hole.id());
					}
				}
			}
			return CommandSource.suggestMatching(ids, builder);
		};
	}

	private static CommandSyntaxException fail(HoleException e) {
		return new SimpleCommandExceptionType(e.text()).create();
	}

	private static void feedback(ServerCommandSource source, Text message) {
		source.sendFeedback(() -> HoleControl.prefixed(message), true);
	}

	private static String fmt(double value, int decimals) {
		return HoleControl.fmt(value, decimals);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Subcommands
	// ------------------------------------------------------------------------------------------------------------

	private static int spawn(CommandContext<ServerCommandSource> ctx, String id, Vec3d pos, boolean white) throws CommandSyntaxException {
		try {
			BlackHoleData data = HoleControl.spawn(ctx.getSource().getWorld(), id, pos, white);
			feedback(ctx.getSource(), Text.translatable(KEY + "spawn.success", HoleControl.label(data),
				fmt(data.x(), 2), fmt(data.y(), 2), fmt(data.z(), 2), fmt(data.mass(), 2), fmt(data.schwarzschildRadius(), 3)));
			return Command.SINGLE_SUCCESS;
		} catch (HoleException e) {
			throw fail(e);
		}
	}

	private static int list(ServerCommandSource source, boolean white) {
		List<Text> lines = new ArrayList<>();
		for (ServerWorld world : source.getServer().getWorlds()) {
			for (BlackHoleData hole : BlackHoleManager.holes(world, white)) {
				String distance = world == source.getWorld() ? fmt(hole.pos().distanceTo(source.getPosition()), 0) : "-";
				lines.add(Text.translatable(KEY + "list.entry", hole.id(), world.getRegistryKey().getValue().toString(),
					fmt(hole.x(), 1), fmt(hole.y(), 1), fmt(hole.z(), 1), fmt(hole.mass(), 3), distance,
					Text.translatable(KEY + (hole.frozen() ? "list.frozen" : hole.stable() ? "list.stable" : "list.active")))
					.formatted(hole.frozen() ? Formatting.AQUA : Formatting.GRAY));
			}
		}
		if (lines.isEmpty()) {
			source.sendFeedback(() -> HoleControl.prefixed(Text.translatable(KEY + (white ? "list.none_white" : "list.none_black"))), false);
			return 0;
		}
		int count = lines.size();
		source.sendFeedback(() -> HoleControl.prefixed(Text.translatable(KEY + (white ? "list.header_white" : "list.header_black"), count)), false);
		for (Text line : lines) {
			source.sendFeedback(() -> line, false);
		}
		return count;
	}

	private static int setMass(CommandContext<ServerCommandSource> ctx, Located target) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withMass(DoubleArgumentType.getDouble(ctx, "value")));
		feedback(ctx.getSource(), Text.translatable(KEY + "set.mass", HoleControl.label(data), fmt(data.mass(), 3),
			fmt(data.schwarzschildRadius(), 3)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setSpin(CommandContext<ServerCommandSource> ctx, Located target) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withSpin(DoubleArgumentType.getDouble(ctx, "value")));
		double rs = data.schwarzschildRadius();
		feedback(ctx.getSource(), Text.translatable(KEY + "set.spin", HoleControl.label(data), fmt(data.spin(), 3),
			fmt(data.horizonRadius(), 3), fmt(Relativity.iscoRadius(rs, data.spin()), 3)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setAccretionDisk(CommandContext<ServerCommandSource> ctx, Located target) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withAccretionDisk(BoolArgumentType.getBool(ctx, "value")));
		feedback(ctx.getSource(), Text.translatable(KEY + (data.accretionDisk() ? "set.accretion_disk.enabled" : "set.accretion_disk.disabled"),
			HoleControl.label(data)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setTemperature(CommandContext<ServerCommandSource> ctx, Located target) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withTemperature(DoubleArgumentType.getDouble(ctx, "value")));
		feedback(ctx.getSource(), Text.translatable(KEY + "set.temperature", HoleControl.label(data), fmt(data.temperature(), 0)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setScale(CommandContext<ServerCommandSource> ctx, Located target) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withScale(DoubleArgumentType.getDouble(ctx, "value")));
		feedback(ctx.getSource(), Text.translatable(KEY + "set.scale", HoleControl.label(data), fmt(data.scale(), 2),
			fmt(data.schwarzschildRadius(), 3)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setStable(CommandContext<ServerCommandSource> ctx, Located target) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withStable(BoolArgumentType.getBool(ctx, "value")));
		feedback(ctx.getSource(), Text.translatable(KEY + (data.stable() ? "set.stable.enabled" : "set.stable.disabled"), HoleControl.label(data)));
		return Command.SINGLE_SUCCESS;
	}

	/** Moves the hole in place: everything but the position is kept (unlike a re-spawn, which resets it all). */
	private static int setPosition(CommandContext<ServerCommandSource> ctx, Located target) throws CommandSyntaxException, HoleException {
		Vec3d pos = Vec3ArgumentType.getVec3(ctx, "pos");
		BlackHoleData data = HoleControl.move(target, pos);
		feedback(ctx.getSource(), Text.translatable(KEY + "set.position", HoleControl.label(data),
			fmt(data.x(), 2), fmt(data.y(), 2), fmt(data.z(), 2)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setPullRadius(CommandContext<ServerCommandSource> ctx, Located target) {
		double radius = DoubleArgumentType.getDouble(ctx, "radius");
		BlackHoleData data = HoleControl.update(target, hole -> hole.withPullRadius(radius));
		feedback(ctx.getSource(), data.pullRadius() > 0.0
			? Text.translatable(KEY + "set.pull_radius", HoleControl.label(data), fmt(data.pullRadius(), 1))
			: Text.translatable(KEY + "set.pull_radius.auto", HoleControl.label(data), fmt(data.influenceRadius(), 1)));
		return Command.SINGLE_SUCCESS;
	}

	private static int massAdd(CommandContext<ServerCommandSource> ctx, Located target) {
		double amount = DoubleArgumentType.getDouble(ctx, "amount");
		BlackHoleData data = HoleControl.addMass(target, amount);
		feedback(ctx.getSource(), Text.translatable(KEY + "mass_add", HoleControl.label(data), fmt(amount, 3), fmt(data.mass(), 3),
			fmt(data.schwarzschildRadius(), 3)));
		return Command.SINGLE_SUCCESS;
	}

	private static int freeze(CommandContext<ServerCommandSource> ctx, Located target, boolean frozen) {
		BlackHoleData data = HoleControl.update(target, hole -> hole.withFrozen(frozen));
		feedback(ctx.getSource(), Text.translatable(KEY + (frozen ? "freeze" : "unfreeze"), HoleControl.label(data)));
		return Command.SINGLE_SUCCESS;
	}

	private static int teleport(CommandContext<ServerCommandSource> ctx, Located target) throws CommandSyntaxException {
		Entity entity = ctx.getSource().getEntityOrThrow();
		HoleControl.teleportTo(entity, target);
		feedback(ctx.getSource(), Text.translatable(KEY + "teleport", HoleControl.label(target.hole()),
			target.world().getRegistryKey().getValue().toString()));
		return Command.SINGLE_SUCCESS;
	}

	private static int inspect(CommandContext<ServerCommandSource> ctx, Located target) {
		for (Text line : HoleControl.describe(target)) {
			ctx.getSource().sendFeedback(() -> line, false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int destroy(CommandContext<ServerCommandSource> ctx, Located target) {
		HoleControl.remove(target);
		feedback(ctx.getSource(), Text.translatable(KEY + "destroy.success", HoleControl.label(target.hole())));
		return Command.SINGLE_SUCCESS;
	}

	/** Removes every hole of the kind in every loaded dimension; returns the number removed. */
	private static int clearAll(ServerCommandSource source, boolean white) throws CommandSyntaxException {
		int removed = HoleControl.clearAll(source.getServer(), white);
		if (removed == 0) {
			throw new SimpleCommandExceptionType(Text.translatable(KEY + (white ? "error.no_white_holes" : "error.no_black_holes"))).create();
		}
		feedback(source, Text.translatable(KEY + (white ? "clear_all.success_white" : "clear_all.success"), removed));
		return removed;
	}

	private static int link(CommandContext<ServerCommandSource> ctx, Located blackHole, Located whiteHole) throws HoleException {
		BlackHoleData data = HoleControl.link(blackHole, whiteHole);
		feedback(ctx.getSource(), Text.translatable(KEY + "link", HoleControl.label(data), HoleControl.label(whiteHole.hole()),
			whiteHole.world().getRegistryKey().getValue().toString()));
		return Command.SINGLE_SUCCESS;
	}

	private static int unlink(CommandContext<ServerCommandSource> ctx, Located blackHole) throws HoleException {
		BlackHoleData data = HoleControl.unlink(blackHole);
		feedback(ctx.getSource(), Text.translatable(KEY + "unlink", HoleControl.label(data)));
		return Command.SINGLE_SUCCESS;
	}

	/** {@code /whitehole unlink}: closes every wormhole that leads to this white hole. */
	private static int unlinkAll(CommandContext<ServerCommandSource> ctx, Located whiteHole) throws HoleException {
		var feeders = BlackHoleManager.linkedTo(whiteHole.world().getServer(), whiteHole.ref());
		if (feeders.isEmpty()) {
			throw new HoleException(Text.translatable(KEY + "error.no_link", HoleControl.label(whiteHole.hole())));
		}
		for (Located blackHole : feeders) {
			HoleControl.unlink(blackHole);
		}
		feedback(ctx.getSource(), Text.translatable(KEY + "unlink.all", HoleControl.label(whiteHole.hole()), feeders.size()));
		return feeders.size();
	}

	/**
	 * {@code /blackhole whitehole set}: as in single-hole versions, the nearest black hole's wormhole gets a white
	 * hole at the position, which may be in another dimension. Its present white hole is moved there; without one, a
	 * new white hole is made.
	 */
	private static int legacySetWhiteHole(CommandContext<ServerCommandSource> ctx, Vec3d pos, ServerWorld target) throws CommandSyntaxException {
		ServerCommandSource source = ctx.getSource();
		try {
			if (target.getRegistryKey().equals(SingularityTransit.DIMENSION)) {
				throw new HoleException(Text.translatable(KEY + "error.white_hole_dimension"));
			}
			Located blackHole = HoleControl.require(source.getWorld(), source.getPosition(), null, false);
			if (target == blackHole.world() && pos.distanceTo(blackHole.hole().pos()) < blackHole.hole().influenceRadius()) {
				throw new HoleException(Text.translatable(KEY + "error.white_hole_too_close"));
			}
			var current = BlackHoleManager.whiteHoleOf(source.getServer(), blackHole.hole());
			Located whiteHole;
			if (current.isPresent()) {
				BlackHoleData moved = current.get().hole().withPos(pos);
				if (current.get().world() != target) {
					// Same id, new dimension: take it out of the old one first (which also closes its wormholes).
					var feeders = BlackHoleManager.linkedTo(source.getServer(), current.get().ref());
					BlackHoleManager.remove(current.get().world(), moved.id());
					BlackHoleManager.put(target, moved);
					whiteHole = new Located(target, moved);
					for (Located feeder : feeders) {
						HoleControl.link(new Located(feeder.world(), BlackHoleManager.get(feeder.world(), feeder.hole().id()).orElse(feeder.hole())), whiteHole);
					}
				} else {
					BlackHoleManager.put(target, moved);
					whiteHole = new Located(target, moved);
				}
			} else {
				whiteHole = new Located(target, HoleControl.spawn(target, null, pos, true));
			}
			HoleControl.link(new Located(blackHole.world(), BlackHoleManager.get(blackHole.world(), blackHole.hole().id())
				.orElse(blackHole.hole())), whiteHole);
			feedback(source, Text.translatable(KEY + "whitehole.set", HoleControl.label(blackHole.hole()), HoleControl.label(whiteHole.hole()),
				target.getRegistryKey().getValue().toString(), fmt(pos.x, 2), fmt(pos.y, 2), fmt(pos.z, 2)));
			return Command.SINGLE_SUCCESS;
		} catch (HoleException e) {
			throw fail(e);
		}
	}

	/** {@code /blackhole whitehole remove}: closes the nearest black hole's wormhole and removes its white hole. */
	private static int legacyRemoveWhiteHole(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerCommandSource source = ctx.getSource();
		try {
			Located blackHole = HoleControl.require(source.getWorld(), source.getPosition(), null, false);
			var whiteHole = BlackHoleManager.whiteHoleOf(source.getServer(), blackHole.hole());
			if (whiteHole.isEmpty()) {
				throw new HoleException(Text.translatable(KEY + "error.no_white_hole_link"));
			}
			HoleControl.remove(whiteHole.get());
			feedback(source, Text.translatable(KEY + "whitehole.removed", HoleControl.label(blackHole.hole()),
				HoleControl.label(whiteHole.get().hole())));
			return Command.SINGLE_SUCCESS;
		} catch (HoleException e) {
			throw fail(e);
		}
	}
}
