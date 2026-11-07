package com.prof.blackhole.data;

import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.wormhole.WhiteHoleLink;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

/**
 * Per-dimension persistent storage, saved to {@code <dimension>/data/profs_black_hole.dat}: the dimension's black
 * and white holes (by id), any Mini Singularity Injector collapses still counting down, and the Gravity
 * Stabilizers placed there.
 * <p>
 * Saves from before multiple holes (a single {@code BlackHole}, its {@code WhiteHole} link and one
 * {@code Collapse}) load as a hole with a provisional id; {@link BlackHoleManager} assigns final ids and turns the
 * old white-hole link into a real white hole once every dimension has loaded.
 */
public final class BlackHoleState extends PersistentState {
	public static final String ID = ProfsBlackHole.MOD_ID;
	/** Provisional id of a hole loaded from a single-hole save. */
	public static final String LEGACY_ID = "bh1";
	private static final String KEY_HOLES = "Holes";
	private static final String KEY_COLLAPSES = "Collapses";
	private static final String KEY_TICKS_LEFT = "TicksLeft";
	private static final String KEY_STABILIZERS = "Stabilizers";
	private static final String KEY_LEGACY_BLACK_HOLE = "BlackHole";
	private static final String KEY_LEGACY_WHITE_HOLE = "WhiteHole";
	private static final String KEY_LEGACY_COLLAPSE = "Collapse";
	private static final String KEY_LEGACY_OWNER = "WhiteHoleOwner";

	// Fabric API (object-builder) makes a null DataFixTypes safe in PersistentStateManager#readNbt.
	private static final PersistentState.Type<BlackHoleState> TYPE =
		new PersistentState.Type<>(BlackHoleState::new, BlackHoleState::fromNbt, null);

	/** A Mini Singularity Injector collapse in progress: the hole it will form and the ticks it has left. */
	public static final class Collapse {
		private final BlackHoleData hole;
		private int ticksLeft;
		/** Progress toward the next click of the countdown's ticking; only sets the rhythm, so not saved. */
		public double clickPhase;

		Collapse(BlackHoleData hole, int ticksLeft) {
			this.hole = hole;
			this.ticksLeft = ticksLeft;
		}

		public BlackHoleData hole() {
			return hole;
		}

		public int ticksLeft() {
			return ticksLeft;
		}
	}

	private final Map<String, BlackHoleData> holes = new LinkedHashMap<>();
	private final List<Collapse> collapses = new ArrayList<>();
	private final LongSet stabilizers = new LongOpenHashSet();
	/** The white-hole link of a single-hole save, converted into a white hole after loading, and its black hole. */
	@Nullable
	private WhiteHoleLink legacyWhiteHole;
	@Nullable
	private String legacyWhiteHoleOwner;

	private BlackHoleState() {
	}

	public static BlackHoleState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, ID);
	}

	/** The dimension's holes, black and white, in creation order (read-only view). */
	public Collection<BlackHoleData> holes() {
		return Collections.unmodifiableCollection(holes.values());
	}

	public Optional<BlackHoleData> hole(String id) {
		return Optional.ofNullable(holes.get(id));
	}

	void put(BlackHoleData data) {
		holes.put(data.id(), data);
		markDirty();
	}

	@Nullable
	BlackHoleData remove(String id) {
		BlackHoleData removed = holes.remove(id);
		if (removed != null) {
			markDirty();
		}
		return removed;
	}

	public List<Collapse> collapses() {
		return Collections.unmodifiableList(collapses);
	}

	public Collapse addCollapse(BlackHoleData hole, int ticks) {
		Collapse collapse = new Collapse(hole, ticks);
		collapses.add(collapse);
		markDirty();
		return collapse;
	}

	public void removeCollapse(Collapse collapse) {
		collapses.remove(collapse);
		markDirty();
	}

	public void setTicksLeft(Collapse collapse, int ticks) {
		collapse.ticksLeft = ticks;
		markDirty();
	}

	/** Positions of the Gravity Stabilizers in this dimension, as {@link BlockPos#asLong} (read-only view). */
	public LongSet stabilizers() {
		return it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(stabilizers);
	}

	public void addStabilizer(BlockPos pos) {
		if (stabilizers.add(pos.asLong())) {
			markDirty();
		}
	}

	public void removeStabilizer(BlockPos pos) {
		if (stabilizers.remove(pos.asLong())) {
			markDirty();
		}
	}

	@Nullable
	WhiteHoleLink legacyWhiteHole() {
		return legacyWhiteHole;
	}

	/** Id of the black hole the single-hole save's white hole belonged to. */
	@Nullable
	String legacyWhiteHoleOwner() {
		return legacyWhiteHoleOwner;
	}

	void clearLegacyWhiteHole() {
		legacyWhiteHole = null;
		legacyWhiteHoleOwner = null;
		markDirty();
	}

	/** Renames a hole (keeping its place in the creation order), fixing nothing else. */
	void rename(String from, String to) {
		Map<String, BlackHoleData> copy = new LinkedHashMap<>(holes);
		holes.clear();
		for (var entry : copy.entrySet()) {
			BlackHoleData hole = entry.getValue();
			if (entry.getKey().equals(from)) {
				hole = hole.withId(to);
			}
			holes.put(hole.id(), hole);
		}
		if (from.equals(legacyWhiteHoleOwner)) {
			legacyWhiteHoleOwner = to;
		}
		markDirty();
	}

	private static BlackHoleState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		BlackHoleState state = new BlackHoleState();
		NbtList holes = nbt.getList(KEY_HOLES, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < holes.size(); i++) {
			BlackHoleData hole = BlackHoleData.fromNbt(holes.getCompound(i), "hole" + (i + 1));
			state.holes.putIfAbsent(hole.id(), hole);
		}
		String owner = nbt.getString(KEY_LEGACY_OWNER);
		if (nbt.contains(KEY_LEGACY_BLACK_HOLE, NbtElement.COMPOUND_TYPE)) {
			BlackHoleData hole = BlackHoleData.fromNbt(nbt.getCompound(KEY_LEGACY_BLACK_HOLE), LEGACY_ID);
			state.holes.putIfAbsent(hole.id(), hole);
			owner = hole.id();
		}
		if (nbt.contains(KEY_LEGACY_WHITE_HOLE, NbtElement.COMPOUND_TYPE) && state.holes.containsKey(owner)) {
			state.legacyWhiteHole = WhiteHoleLink.fromNbt(nbt.getCompound(KEY_LEGACY_WHITE_HOLE));
			state.legacyWhiteHoleOwner = state.legacyWhiteHole == null ? null : owner;
		}
		NbtList collapses = nbt.getList(KEY_COLLAPSES, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < collapses.size(); i++) {
			NbtCompound collapse = collapses.getCompound(i);
			state.collapses.add(new Collapse(BlackHoleData.fromNbt(collapse, ""), Math.max(collapse.getInt(KEY_TICKS_LEFT), 1)));
		}
		if (nbt.contains(KEY_LEGACY_COLLAPSE, NbtElement.COMPOUND_TYPE)) {
			NbtCompound collapse = nbt.getCompound(KEY_LEGACY_COLLAPSE);
			state.collapses.add(new Collapse(BlackHoleData.fromNbt(collapse, ""), Math.max(collapse.getInt(KEY_TICKS_LEFT), 1)));
		}
		for (long pos : nbt.getLongArray(KEY_STABILIZERS)) {
			state.stabilizers.add(pos);
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		NbtList holes = new NbtList();
		for (BlackHoleData hole : this.holes.values()) {
			holes.add(hole.toNbt());
		}
		nbt.put(KEY_HOLES, holes);
		NbtList collapses = new NbtList();
		for (Collapse collapse : this.collapses) {
			NbtCompound pending = collapse.hole.toNbt();
			pending.putInt(KEY_TICKS_LEFT, collapse.ticksLeft);
			collapses.add(pending);
		}
		nbt.put(KEY_COLLAPSES, collapses);
		nbt.putLongArray(KEY_STABILIZERS, stabilizers.toLongArray());
		if (legacyWhiteHole != null && legacyWhiteHoleOwner != null) {
			// Not converted yet (its dimension is not loaded): kept for the next start.
			nbt.put(KEY_LEGACY_WHITE_HOLE, legacyWhiteHole.toNbt());
			nbt.putString(KEY_LEGACY_OWNER, legacyWhiteHoleOwner);
		}
		return nbt;
	}
}
