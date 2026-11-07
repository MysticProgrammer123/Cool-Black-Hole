package com.prof.blackhole.world;

import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.data.BlackHoleManager;
import com.prof.blackhole.data.BlackHoleState;
import com.prof.blackhole.data.BlackHoleState.Collapse;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * A Mini Singularity Injector does not open a hole at once: the injected matter collapses for one minute
 * ({@value #COLLAPSE_TICKS} ticks) and only then forms the hole. A 1 M_J hole kills anything within a few blocks
 * and drags in everything within a hundred, so the minute is the escape window. Everyone nearby is warned:
 * <ul>
 *     <li>particles pulse around the injection point (red and purple for an implosion, green and violet for an
 *     evaporating micro hole, cyan and blue for a stationary one), faster and denser as the end nears, while portal
 *     light streams into it;</li>
 *     <li>a ticking clock, once a second, then accelerating to ten ticks a second through the final ten seconds;</li>
 *     <li>an action-bar countdown every second, and titles at the start, at 30 seconds and for each of the last
 *     ten seconds.</li>
 * </ul>
 * Several collapses can run at once, each with its own countdown. Pending collapses are saved with the dimension,
 * so they survive a restart.
 */
public final class SingularityCollapses {
	public static final int COLLAPSE_TICKS = 1200;
	/** At most this many collapses count down in one dimension at a time. */
	public static final int MAX_COLLAPSES = 8;
	/** The ticking accelerates and the titles count down over the last this many ticks. */
	private static final int FINAL_COUNTDOWN_TICKS = 200;
	/** Ticks between clicks before the final countdown, and at its very end. */
	private static final double SLOW_TICK_INTERVAL = 20.0;
	private static final double FAST_TICK_INTERVAL = 2.0;
	/** Players within this distance get the countdown, blocks. */
	private static final double WARNING_RANGE = 96.0;
	/** A new injection needs this much room from other collapses, blocks. */
	private static final double MIN_SPACING = 3.0;
	private static final Vector3f RED = new Vector3f(1.0F, 0.1F, 0.16F);
	private static final Vector3f PURPLE = new Vector3f(0.62F, 0.12F, 1.0F);
	private static final Vector3f GREEN = new Vector3f(0.45F, 1.0F, 0.35F);
	private static final Vector3f VIOLET = new Vector3f(0.68F, 0.3F, 1.0F);
	private static final Vector3f CYAN = new Vector3f(0.35F, 0.95F, 1.0F);
	private static final Vector3f BLUE = new Vector3f(0.2F, 0.35F, 1.0F);

	private SingularityCollapses() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(SingularityCollapses::tick);
	}

	/** Why a hole cannot be injected at {@code pos}, or empty when it can. */
	public static Optional<Text> blocked(ServerWorld world, Vec3d pos) {
		if (world.getRegistryKey().equals(com.prof.blackhole.wormhole.SingularityTransit.DIMENSION)) {
			return Optional.of(Text.translatable("command.profs_black_hole.error.singularity_dimension"));
		}
		BlackHoleState state = BlackHoleState.get(world);
		if (state.holes().size() + state.collapses().size() >= BlackHoleManager.MAX_HOLES_PER_DIMENSION) {
			return Optional.of(Text.translatable("item.profs_black_hole.mini_singularity_injector.full",
				BlackHoleManager.MAX_HOLES_PER_DIMENSION));
		}
		if (state.collapses().size() >= MAX_COLLAPSES) {
			return Optional.of(Text.translatable("item.profs_black_hole.mini_singularity_injector.busy", MAX_COLLAPSES));
		}
		for (Collapse collapse : state.collapses()) {
			if (collapse.hole().pos().distanceTo(pos) < MIN_SPACING) {
				return Optional.of(Text.translatable("item.profs_black_hole.mini_singularity_injector.occupied"));
			}
		}
		for (BlackHoleData hole : state.holes()) {
			if (hole.pos().distanceTo(pos) < hole.horizonRadius() + 1.0) {
				return Optional.of(Text.translatable("item.profs_black_hole.mini_singularity_injector.occupied"));
			}
		}
		return Optional.empty();
	}

	public static void start(ServerWorld world, BlackHoleData hole, Text name) {
		BlackHoleState.get(world).addCollapse(hole, COLLAPSE_TICKS);
		world.playSound(null, hole.x(), hole.y(), hole.z(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS,
			2.0F, 0.5F);
		title(world, hole.pos(), Text.translatable("item.profs_black_hole.mini_singularity_injector.countdown.start", name)
			.formatted(Formatting.LIGHT_PURPLE), COLLAPSE_TICKS);
	}

	private static void tick(ServerWorld world) {
		BlackHoleState state = BlackHoleState.get(world);
		List<Collapse> collapses = List.copyOf(state.collapses());
		for (Collapse collapse : collapses) {
			tick(world, state, collapse);
		}
	}

	private static void tick(ServerWorld world, BlackHoleState state, Collapse collapse) {
		BlackHoleData hole = collapse.hole();
		Vec3d pos = hole.pos();
		int left = collapse.ticksLeft() - 1;
		state.setTicksLeft(collapse, left);
		double progress = 1.0 - (double) left / COLLAPSE_TICKS;

		pulse(world, hole, left, progress);
		streams(world, pos, progress);
		clock(world, collapse, pos, left);
		if (left > 0 && left % 20 == 0) {
			countdown(world, pos, left);
		}
		if (left == 60) {
			world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, SoundCategory.BLOCKS,
				3.0F, 0.5F);
		}
		if (left > 0) {
			return;
		}

		state.removeCollapse(collapse);
		if (BlackHoleManager.count(world) >= BlackHoleManager.MAX_HOLES_PER_DIMENSION) {
			return;
		}
		String id = BlackHoleData.isValidId(hole.id()) && !BlackHoleManager.isIdTaken(world.getServer(), hole.id())
			? hole.id() : BlackHoleManager.nextId(world.getServer(), false);
		BlackHoleManager.put(world, hole.withId(id).withBornTime(world.getTime()));
		BlackHoleEffects.shockwave(world, pos, 1.0F);
	}

	/** A shell of sparks breathing in and out, faster and denser toward the end. */
	private static void pulse(ServerWorld world, BlackHoleData hole, int left, double progress) {
		double period = left > FINAL_COUNTDOWN_TICKS ? 40.0 : 8.0 + 32.0 * left / FINAL_COUNTDOWN_TICKS;
		double beat = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * left / period);
		double radius = 0.9 + 0.9 * beat;
		int count = 6 + (int) (10 * progress);
		float size = (float) (0.9 + 0.8 * beat);
		Vector3f first = hole.stable() ? CYAN : hole.mass() < 0.5 ? GREEN : RED;
		Vector3f second = hole.stable() ? BLUE : hole.mass() < 0.5 ? VIOLET : PURPLE;
		for (int i = 0; i < count; i++) {
			Vec3d dir = randomDirection(world);
			boolean a = (i & 1) == 0;
			var dust = new DustColorTransitionParticleEffect(a ? first : second, a ? second : first, size);
			BlackHoleEffects.particle(world, dust, hole.pos().add(dir.multiply(radius)), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Light drawn into the collapsing core, faster and denser as it nears the end. */
	private static void streams(ServerWorld world, Vec3d pos, double progress) {
		int streams = 2 + (int) (6 * progress);
		double reach = 4.0 - 2.5 * progress;
		for (int i = 0; i < streams; i++) {
			Vec3d offset = randomDirection(world).multiply(reach);
			BlackHoleEffects.particle(world, ParticleTypes.PORTAL, pos, 0, offset.x, offset.y, offset.z, 1.0);
		}
	}

	/** Once a second, then accelerating (and rising in pitch) to ten clicks a second at the end. */
	private static void clock(ServerWorld world, Collapse collapse, Vec3d pos, int left) {
		double interval;
		float pitch;
		if (left > FINAL_COUNTDOWN_TICKS) {
			interval = SLOW_TICK_INTERVAL;
			pitch = 0.8F;
		} else {
			double f = (double) left / FINAL_COUNTDOWN_TICKS;
			interval = FAST_TICK_INTERVAL + (SLOW_TICK_INTERVAL - FAST_TICK_INTERVAL) * f;
			pitch = (float) (0.9 + 1.0 * (1.0 - f));
		}
		collapse.clickPhase += 1.0 / interval;
		if (collapse.clickPhase >= 1.0) {
			collapse.clickPhase -= 1.0;
			world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.BLOCKS, 2.0F, pitch);
			if (left <= FINAL_COUNTDOWN_TICKS) {
				world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.BLOCKS,
					1.5F, pitch * 0.5F);
			}
		}
	}

	private static void countdown(ServerWorld world, Vec3d pos, int left) {
		int seconds = left / 20;
		boolean last = left <= FINAL_COUNTDOWN_TICKS;
		String clock = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
		Text bar = Text.translatable("item.profs_black_hole.mini_singularity_injector.countdown", clock)
			.formatted(last ? Formatting.RED : Formatting.LIGHT_PURPLE);
		for (ServerPlayerEntity player : playersNear(world, pos)) {
			player.sendMessage(bar, true);
		}
		if (last || seconds == 30) {
			title(world, pos, Text.literal(Integer.toString(seconds)).formatted(last ? Formatting.RED : Formatting.LIGHT_PURPLE,
				Formatting.BOLD), left);
		}
	}

	private static void title(ServerWorld world, Vec3d pos, Text title, int left) {
		Text subtitle = Text.translatable("item.profs_black_hole.mini_singularity_injector.countdown.subtitle", left / 20)
			.formatted(Formatting.DARK_PURPLE);
		for (ServerPlayerEntity player : playersNear(world, pos)) {
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 22, 4));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
			player.networkHandler.sendPacket(new TitleS2CPacket(title));
		}
	}

	private static Iterable<ServerPlayerEntity> playersNear(ServerWorld world, Vec3d pos) {
		return world.getPlayers(player -> player.squaredDistanceTo(pos) < WARNING_RANGE * WARNING_RANGE);
	}

	private static Vec3d randomDirection(ServerWorld world) {
		double y = world.random.nextDouble() * 2.0 - 1.0;
		double phi = world.random.nextDouble() * 2.0 * Math.PI;
		double rho = Math.sqrt(1.0 - y * y);
		return new Vec3d(rho * Math.cos(phi), y, rho * Math.sin(phi));
	}
}
