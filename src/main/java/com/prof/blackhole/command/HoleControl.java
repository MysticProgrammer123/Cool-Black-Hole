package com.prof.blackhole.command;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.BlackHoleManager.Located;
import com.prof.blackhole.data.HoleRef;
import com.prof.blackhole.physics.Relativity;
import com.prof.blackhole.world.BlackHoleEffects;
import com.prof.blackhole.world.BlackHoleEvolution;
import com.prof.blackhole.world.HawkingRadiation;
import com.prof.blackhole.world.SpacetimeCorruption;
import com.prof.blackhole.wormhole.SingularityTransit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * The operations behind both the {@code /blackhole} and {@code /whitehole} commands and the control panel, so the
 * two always behave alike. Every failure is a {@link HoleException} carrying the message to show.
 */
public final class HoleControl {
	public static final String KEY = "command.profs_black_hole.";
	/** The chat prefix of every message the mod sends. */
	public static final Text PREFIX = Text.literal("[Black Hole] ").formatted(Formatting.DARK_PURPLE);

	/** A failed operation, with its message. */
	public static final class HoleException extends Exception {
		private final Text text;

		public HoleException(Text text) {
			super(text.getString(), null, false, false);
			this.text = text;
		}

		public Text text() {
			return text;
		}
	}

	private HoleControl() {
	}

	/** {@code [Black Hole] } followed by the message. */
	public static MutableText prefixed(Text message) {
		return Text.empty().append(PREFIX).append(message);
	}

	/** "Black hole bh1" or "White hole wh2". */
	public static MutableText label(BlackHoleData hole) {
		return Text.translatable(KEY + (hole.white() ? "label.white" : "label.black"), hole.id());
	}

	public static String fmt(double value, int decimals) {
		return String.format(Locale.ROOT, "%." + decimals + "f", value);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Lookup
	// ------------------------------------------------------------------------------------------------------------

	/**
	 * The hole a command or the panel acts on: the one with {@code id} (in any dimension), or without an id the
	 * nearest one of that kind in {@code world}.
	 */
	public static Located require(ServerWorld world, Vec3d near, @Nullable String id, boolean white) throws HoleException {
		if (id != null) {
			var found = BlackHoleManager.find(world.getServer(), id);
			if (found.isEmpty() || found.get().hole().white() != white) {
				throw new HoleException(Text.translatable(KEY + (white ? "error.no_white_hole_id" : "error.no_black_hole_id"), id));
			}
			return found.get();
		}
		return BlackHoleManager.nearest(world, near, white).map(hole -> new Located(world, hole))
			.orElseThrow(() -> new HoleException(Text.translatable(KEY + (white ? "error.no_white_hole" : "error.no_black_hole"))));
	}

	/** Either kind, by id or nearest. */
	public static Located requireAny(ServerWorld world, Vec3d near, @Nullable String id) throws HoleException {
		if (id != null) {
			return BlackHoleManager.find(world.getServer(), id)
				.orElseThrow(() -> new HoleException(Text.translatable(KEY + "error.no_hole_id", id)));
		}
		return BlackHoleManager.nearest(world, near, null).map(hole -> new Located(world, hole))
			.orElseThrow(() -> new HoleException(Text.translatable(KEY + "error.no_hole")));
	}

	// ------------------------------------------------------------------------------------------------------------
	// Mutations
	// ------------------------------------------------------------------------------------------------------------

	/** Creates a hole with default parameters ({@code id} null: the next free {@code bh<n>} / {@code wh<n>}). */
	public static BlackHoleData spawn(ServerWorld world, @Nullable String id, Vec3d pos, boolean white) throws HoleException {
		MinecraftServer server = world.getServer();
		if (world.getRegistryKey().equals(SingularityTransit.DIMENSION)) {
			throw new HoleException(Text.translatable(KEY + "error.singularity_dimension"));
		}
		if (id != null) {
			if (!BlackHoleData.isValidId(id)) {
				throw new HoleException(Text.translatable(KEY + "error.invalid_id", id, BlackHoleData.MAX_ID_LENGTH));
			}
			if (BlackHoleManager.isIdTaken(server, id)) {
				throw new HoleException(Text.translatable(KEY + "error.id_taken", id));
			}
		}
		if (BlackHoleManager.count(world) >= BlackHoleManager.MAX_HOLES_PER_DIMENSION) {
			throw new HoleException(Text.translatable(KEY + "error.full", BlackHoleManager.MAX_HOLES_PER_DIMENSION));
		}
		requireClear(world, pos, null);
		BlackHoleData data = BlackHoleData.create(id != null ? id : BlackHoleManager.nextId(server, white), white, pos, world.getTime());
		BlackHoleManager.put(world, data);
		BlackHoleEffects.shockwave(world, data.pos(), white ? 0.8F : 1.0F);
		return data;
	}

	/** Applies a change to the hole and saves it; returns the new state. */
	public static BlackHoleData update(Located target, UnaryOperator<BlackHoleData> change) {
		BlackHoleData updated = change.apply(target.hole());
		BlackHoleManager.put(target.world(), updated);
		return updated;
	}

	/** Moves the hole within its dimension, keeping everything else. */
	public static BlackHoleData move(Located target, Vec3d pos) throws HoleException {
		requireClear(target.world(), pos, target.hole().id());
		return update(target, hole -> hole.withPos(pos));
	}

	public static BlackHoleData addMass(Located target, double amount) {
		return update(target, hole -> hole.withMass(hole.mass() + amount));
	}

	/**
	 * Leads the black hole's wormhole to the white hole. In one dimension the white hole must lie outside the black
	 * hole's pull (expelled matter would only fall straight back in).
	 */
	public static BlackHoleData link(Located blackHole, Located whiteHole) throws HoleException {
		if (blackHole.hole().white() || !whiteHole.hole().white()) {
			throw new HoleException(Text.translatable(KEY + "error.link_kinds"));
		}
		if (whiteHole.world() == blackHole.world()
			&& whiteHole.hole().pos().distanceTo(blackHole.hole().pos()) < blackHole.hole().influenceRadius()) {
			throw new HoleException(Text.translatable(KEY + "error.white_hole_too_close"));
		}
		HoleRef ref = whiteHole.ref();
		return update(blackHole, hole -> hole.withLink(Optional.of(ref)));
	}

	public static BlackHoleData unlink(Located blackHole) throws HoleException {
		if (blackHole.hole().link().isEmpty()) {
			throw new HoleException(Text.translatable(KEY + "error.no_link", label(blackHole.hole())));
		}
		return update(blackHole, hole -> hole.withLink(Optional.empty()));
	}

	public static void remove(Located target) {
		BlackHoleManager.remove(target.world(), target.hole().id());
	}

	/** Removes every hole of one kind in every loaded dimension; returns how many. */
	public static int clearAll(MinecraftServer server, boolean white) {
		int removed = 0;
		for (ServerWorld world : server.getWorlds()) {
			for (BlackHoleData hole : BlackHoleManager.holes(world, white)) {
				BlackHoleManager.remove(world, hole.id());
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Takes the entity to a vantage point on the hole's +Z side, level with it and facing it, where the pull is
	 * about half of vanilla gravity (never inside 3 horizon radii, never beyond the hole's reach).
	 */
	public static void teleportTo(Entity entity, Located target) {
		BlackHoleData hole = target.hole();
		double gm = Relativity.gravitationalAcceleration(hole.schwarzschildRadius(), 1.0);
		double distance = MathHelper.clamp(Math.sqrt(gm / 0.04), 3.0 * hole.horizonRadius() + 4.0,
			Math.max(hole.influenceRadius(), 3.0 * hole.horizonRadius() + 4.0));
		Vec3d at = hole.pos().add(0.0, 0.0, distance);
		entity.teleport(target.world(), at.x, at.y, at.z, Set.of(), 180.0F, 0.0F);
		entity.setVelocity(Vec3d.ZERO);
		entity.velocityModified = true;
		entity.fallDistance = 0.0F;
		if (entity instanceof LivingEntity living) {
			living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false, true));
		}
	}

	/** Nothing may be created or moved inside another hole's horizon or core. */
	private static void requireClear(ServerWorld world, Vec3d pos, @Nullable String ignore) throws HoleException {
		for (BlackHoleData other : BlackHoleManager.holes(world)) {
			if (!other.id().equals(ignore) && other.pos().distanceTo(pos) < other.horizonRadius() + 0.5) {
				throw new HoleException(Text.translatable(KEY + "error.inside_hole", label(other)));
			}
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Inspection
	// ------------------------------------------------------------------------------------------------------------

	/** The lines of {@code /blackhole inspect} (and {@code info}). */
	public static List<Text> describe(Located target) {
		BlackHoleData hole = target.hole();
		ServerWorld world = target.world();
		double rs = hole.schwarzschildRadius();
		List<Text> lines = new ArrayList<>();
		lines.add(Text.translatable(KEY + "info.header", label(hole)).formatted(Formatting.GOLD));
		lines.add(Text.translatable(KEY + "info.dimension", world.getRegistryKey().getValue().toString()));
		lines.add(Text.translatable(KEY + "info.position", fmt(hole.x(), 2), fmt(hole.y(), 2), fmt(hole.z(), 2)));
		lines.add(Text.translatable(KEY + "info.mass", fmt(hole.mass(), 4),
			String.format(Locale.ROOT, "%.4e", hole.mass() * Relativity.JUPITER_MASS_KG)));
		lines.add(Text.translatable(KEY + "info.schwarzschild_radius", fmt(rs, 3), fmt(hole.scale(), 2)));
		if (hole.white()) {
			lines.add(Text.translatable(KEY + "info.core", fmt(hole.horizonRadius(), 3)));
		} else {
			lines.add(Text.translatable(KEY + "info.horizon", fmt(hole.horizonRadius(), 3)));
			lines.add(Text.translatable(KEY + "info.isco", fmt(Relativity.iscoRadius(rs, hole.spin()), 3)));
			lines.add(Text.translatable(KEY + "info.photon_sphere", fmt(Relativity.photonSphere(rs), 3)));
		}
		lines.add(Text.translatable(KEY + "info.spin", fmt(hole.spin(), 3)));
		lines.add(Text.translatable(KEY + "info.pull_radius", fmt(hole.influenceRadius(), 1),
			Text.translatable(KEY + (hole.pullRadius() > 0.0 ? "info.pull_radius.fixed" : "info.pull_radius.auto"))));
		lines.add(Text.translatable(KEY + (hole.accretionDisk() ? "info.accretion_disk.enabled" : "info.accretion_disk.disabled")));
		lines.add(Text.translatable(KEY + "info.temperature", fmt(hole.temperature(), 0)));
		if (!hole.white()) {
			lines.add(Text.translatable(KEY + "info.hawking_temperature",
				String.format(Locale.ROOT, "%.4e", Relativity.hawkingTemperature(hole.mass()))));
		}
		String state;
		if (hole.frozen()) {
			state = "frozen";
		} else if (hole.stable()) {
			state = "stable";
		} else if (hole.white()) {
			state = BlackHoleEvolution.isFeeding(world, hole) ? "ejecting" : "gathering";
		} else {
			state = BlackHoleEvolution.isFeeding(world, hole) ? "feeding" : "quiescent";
		}
		lines.add(Text.translatable(KEY + "info.state." + state));
		long age = hole.age(world.getTime());
		lines.add(Text.translatable(KEY + "info.age", fmt(age / 1200.0, 1)));
		if (!hole.white()) {
			if (HawkingRadiation.radiates(hole)) {
				lines.add(Text.translatable(KEY + "info.radiation_zone", fmt(HawkingRadiation.zoneRadius(hole), 1)));
			}
			double corruption = SpacetimeCorruption.radius(hole, world.getTime());
			if (corruption > 0.0) {
				lines.add(Text.translatable(KEY + "info.corruption", fmt(corruption, 1)));
			}
			var whiteHole = BlackHoleManager.whiteHoleOf(world.getServer(), hole);
			if (whiteHole.isPresent()) {
				BlackHoleData exit = whiteHole.get().hole();
				lines.add(Text.translatable(KEY + "info.white_hole", exit.id(), whiteHole.get().world().getRegistryKey().getValue().toString(),
					fmt(exit.x(), 1), fmt(exit.y(), 1), fmt(exit.z(), 1)));
			} else {
				lines.add(Text.translatable(KEY + "info.white_hole.none"));
			}
		} else {
			List<String> feeders = new ArrayList<>();
			for (Located located : BlackHoleManager.linkedTo(world.getServer(), target.ref())) {
				feeders.add(located.hole().id());
			}
			lines.add(feeders.isEmpty() ? Text.translatable(KEY + "info.linked_from.none")
				: Text.translatable(KEY + "info.linked_from", String.join(", ", feeders)));
		}
		return lines;
	}
}
