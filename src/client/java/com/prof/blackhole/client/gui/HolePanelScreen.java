package com.prof.blackhole.client.gui;

import com.prof.blackhole.client.ClientConfig;
import com.prof.blackhole.data.BlackHoleData;
import com.prof.blackhole.network.PanelPayloads;
import com.prof.blackhole.network.PanelPayloads.ActionType;
import com.prof.blackhole.physics.Relativity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * The control panel: every black and white hole of every dimension, and live controls for the selected one (mass,
 * spin, peak temperature, event-horizon scale, pull radius, disk, frozen, stable, its wormhole), plus teleport, move,
 * remove and create, and the player's own lensing setting. Opened with B, or by sneak-using a hole.
 * <p>
 * The server is the authority: the panel shows its snapshot ({@link PanelPayloads.Snapshot}, refreshed every
 * second and after each change), and every change is a request ({@link PanelPayloads.Action}) the server checks
 * and applies exactly as the commands would. Slider drags are batched to a few requests a second.
 */
public final class HolePanelScreen extends Screen {
	private static final String KEY = "gui.profs_black_hole.panel.";
	private static final int ROW = 22;
	private static final int HEIGHT = 20;
	/** Ticks between batched edits, and between refreshes. */
	private static final int SEND_INTERVAL = 3;
	private static final int REFRESH_INTERVAL = 20;
	/** A control just changed by the player ignores server values for this long, ms (they may predate the change). */
	private static final long EDIT_HOLD_MS = 1500L;
	private static final long CONFIRM_MS = 3000L;

	private List<PanelPayloads.Entry> entries = List.of();
	private boolean allowed = true;
	private boolean received;
	@Nullable
	private String selectedId;
	private Text status = Text.empty();
	private boolean statusError;
	private final Map<ActionType, Double> pending = new EnumMap<>(ActionType.class);
	private final Map<ActionType, Long> editedAt = new EnumMap<>(ActionType.class);
	private int ticks;
	private long removeArmedAt;

	private int left;
	private int top;
	private int panelWidth;

	private ButtonWidget prevButton;
	private ButtonWidget selectButton;
	private ButtonWidget nextButton;
	private ValueSlider massSlider;
	private ValueSlider spinSlider;
	private ValueSlider temperatureSlider;
	private ValueSlider scaleSlider;
	private ValueSlider pullSlider;
	private ButtonWidget diskButton;
	private ButtonWidget frozenButton;
	private ButtonWidget stableButton;
	private ButtonWidget linkButton;
	private ButtonWidget lensingButton;
	private ButtonWidget teleportButton;
	private ButtonWidget moveButton;
	private ButtonWidget removeButton;
	private ButtonWidget spawnBlackButton;
	private ButtonWidget spawnWhiteButton;

	public HolePanelScreen(@Nullable String preselect) {
		super(Text.translatable(KEY + "title"));
		this.selectedId = preselect;
	}

	public static void open(@Nullable String preselect) {
		MinecraftClient.getInstance().setScreen(new HolePanelScreen(preselect));
	}

	/** A snapshot from the server; applied if the panel is open. */
	public static void accept(PanelPayloads.Snapshot snapshot) {
		if (MinecraftClient.getInstance().currentScreen instanceof HolePanelScreen screen) {
			screen.onSnapshot(snapshot);
		}
	}

	@Override
	protected void init() {
		panelWidth = Math.min(360, this.width - 16);
		left = (this.width - panelWidth) / 2;
		top = Math.max(16, Math.min(22, (this.height - 236) / 2 + 18));
		int half = (panelWidth - 4) / 2;
		int third = (panelWidth - 8) / 3;
		int right = left + half + 4;

		prevButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> cycle(-1)).dimensions(left, top, 20, HEIGHT).build());
		selectButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> cycle(1))
			.dimensions(left + 24, top, panelWidth - 48, HEIGHT).build());
		nextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> cycle(1))
			.dimensions(left + panelWidth - 20, top, 20, HEIGHT).build());

		int y = top + 46;
		massSlider = addDrawableChild(new ValueSlider(left, y, half, HEIGHT,
			ValueSlider.Mapping.logarithmic(BlackHoleData.MIN_MASS, BlackHoleData.MAX_MASS),
			v -> Text.translatable(KEY + "mass", fmt(v, 3)), v -> edit(ActionType.SET_MASS, v), BlackHoleData.DEFAULT_MASS));
		spinSlider = addDrawableChild(new ValueSlider(right, y, half, HEIGHT,
			ValueSlider.Mapping.linear(BlackHoleData.MIN_SPIN, BlackHoleData.MAX_SPIN),
			v -> Text.translatable(KEY + "spin", fmt(v, 2)), v -> edit(ActionType.SET_SPIN, v), BlackHoleData.DEFAULT_SPIN));
		y += ROW;
		temperatureSlider = addDrawableChild(new ValueSlider(left, y, half, HEIGHT,
			ValueSlider.Mapping.logarithmic(BlackHoleData.MIN_TEMPERATURE, BlackHoleData.MAX_TEMPERATURE),
			v -> Text.translatable(KEY + "temperature", fmt(v, 0)), v -> edit(ActionType.SET_TEMPERATURE, v), BlackHoleData.DEFAULT_TEMPERATURE));
		scaleSlider = addDrawableChild(new ValueSlider(right, y, half, HEIGHT,
			ValueSlider.Mapping.logarithmic(BlackHoleData.MIN_SCALE, BlackHoleData.MAX_SCALE),
			v -> Text.translatable(KEY + "scale", fmt(v, 2)), v -> edit(ActionType.SET_SCALE, v), BlackHoleData.DEFAULT_SCALE));
		y += ROW;
		pullSlider = addDrawableChild(new ValueSlider(left, y, half, HEIGHT,
			ValueSlider.Mapping.autoThenLinear(BlackHoleData.MIN_PULL_RADIUS, BlackHoleData.MAX_PULL_RADIUS),
			v -> v <= 0.0 ? Text.translatable(KEY + "pull_radius.auto") : Text.translatable(KEY + "pull_radius", fmt(v, 0)),
			v -> edit(ActionType.SET_PULL_RADIUS, v), 0.0));
		diskButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> toggle(ActionType.SET_DISK))
			.dimensions(right, y, half, HEIGHT).build());
		y += ROW;
		frozenButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> toggle(ActionType.SET_FROZEN))
			.dimensions(left, y, half, HEIGHT).build());
		stableButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> toggle(ActionType.SET_STABLE))
			.dimensions(right, y, half, HEIGHT).build());
		y += ROW;
		linkButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> cycleLink()).dimensions(left, y, half, HEIGHT).build());
		lensingButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
			ClientConfig.setLensing(!ClientConfig.lensing());
			refreshWidgets();
		}).dimensions(right, y, half, HEIGHT).build());
		y += ROW;
		teleportButton = addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "teleport"), b -> act(ActionType.TELEPORT_TO, 0.0, ""))
			.dimensions(left, y, third, HEIGHT).build());
		moveButton = addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "move_here"), b -> act(ActionType.MOVE_HERE, 0.0, ""))
			.dimensions(left + third + 4, y, third, HEIGHT).build());
		removeButton = addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "remove"), b -> remove())
			.dimensions(left + 2 * (third + 4), y, third, HEIGHT).build());
		y += ROW;
		spawnBlackButton = addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "spawn_black"), b -> spawn(false))
			.dimensions(left, y, third, HEIGHT).build());
		spawnWhiteButton = addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "spawn_white"), b -> spawn(true))
			.dimensions(left + third + 4, y, third, HEIGHT).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
			.dimensions(left + 2 * (third + 4), y, third, HEIGHT).build());

		refreshWidgets();
		request();
	}

	@Override
	public boolean shouldPause() {
		// The holes keep evolving while the panel is open; their live state is the point.
		return false;
	}

	@Override
	public void tick() {
		ticks++;
		if (ticks % SEND_INTERVAL == 0) {
			flush();
		}
		if (ticks % REFRESH_INTERVAL == 0 && pending.isEmpty()) {
			request();
		}
	}

	@Override
	public void removed() {
		flush();
		super.removed();
	}

	// ------------------------------------------------------------------------------------------------------------
	// Server traffic
	// ------------------------------------------------------------------------------------------------------------

	private static void request() {
		if (ClientPlayNetworking.canSend(PanelPayloads.Request.ID)) {
			ClientPlayNetworking.send(new PanelPayloads.Request());
		}
	}

	private void onSnapshot(PanelPayloads.Snapshot snapshot) {
		received = true;
		allowed = snapshot.allowed();
		entries = sorted(snapshot.entries());
		if (!snapshot.message().getString().isEmpty() || snapshot.error()) {
			status = snapshot.message();
			statusError = snapshot.error();
		}
		if (selected() == null) {
			selectedId = entries.isEmpty() ? null : entries.get(0).hole().id();
		}
		refreshWidgets();
	}

	/** The holes of the player's dimension first, nearest first; then the others by dimension and id. */
	private List<PanelPayloads.Entry> sorted(List<PanelPayloads.Entry> list) {
		MinecraftClient client = MinecraftClient.getInstance();
		String here = client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
		Vec3d pos = client.player == null ? Vec3d.ZERO : client.player.getPos();
		List<PanelPayloads.Entry> result = new ArrayList<>(list);
		result.sort(Comparator.<PanelPayloads.Entry>comparingInt(e -> e.dimension().equals(here) ? 0 : 1)
			.thenComparingDouble(e -> e.dimension().equals(here) ? e.hole().pos().squaredDistanceTo(pos) : 0.0)
			.thenComparing(PanelPayloads.Entry::dimension)
			.thenComparing(e -> e.hole().id()));
		return result;
	}

	private void edit(ActionType type, double value) {
		if (selectedId == null || !allowed) {
			return;
		}
		pending.put(type, value);
		editedAt.put(type, System.currentTimeMillis());
	}

	private void flush() {
		if (pending.isEmpty() || selectedId == null) {
			pending.clear();
			return;
		}
		for (var entry : pending.entrySet()) {
			send(new PanelPayloads.Action(selectedId, entry.getKey(), entry.getValue(), ""));
		}
		pending.clear();
	}

	private void act(ActionType type, double value, String text) {
		if (selectedId == null) {
			return;
		}
		flush();
		send(new PanelPayloads.Action(selectedId, type, value, text));
	}

	private static void send(PanelPayloads.Action action) {
		if (ClientPlayNetworking.canSend(PanelPayloads.Action.ID)) {
			ClientPlayNetworking.send(action);
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Controls
	// ------------------------------------------------------------------------------------------------------------

	@Nullable
	private PanelPayloads.Entry selected() {
		for (PanelPayloads.Entry entry : entries) {
			if (entry.hole().id().equals(selectedId)) {
				return entry;
			}
		}
		return null;
	}

	private void cycle(int step) {
		if (entries.isEmpty()) {
			return;
		}
		flush();
		int index = 0;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).hole().id().equals(selectedId)) {
				index = i;
			}
		}
		index = Math.floorMod(index + step, entries.size());
		selectedId = entries.get(index).hole().id();
		editedAt.clear();
		removeArmedAt = 0L;
		refreshWidgets();
	}

	private void toggle(ActionType type) {
		PanelPayloads.Entry entry = selected();
		if (entry == null) {
			return;
		}
		BlackHoleData hole = entry.hole();
		boolean current = switch (type) {
			case SET_DISK -> hole.accretionDisk();
			case SET_FROZEN -> hole.frozen();
			case SET_STABLE -> hole.stable();
			default -> false;
		};
		act(type, current ? 0.0 : 1.0, "");
	}

	/** A black hole's wormhole: none, then each white hole in turn. */
	private void cycleLink() {
		PanelPayloads.Entry entry = selected();
		if (entry == null || entry.hole().white()) {
			return;
		}
		List<String> options = new ArrayList<>();
		options.add("");
		for (PanelPayloads.Entry other : entries) {
			if (other.hole().white()) {
				options.add(other.hole().id());
			}
		}
		String current = entry.hole().link().map(ref -> ref.id()).orElse("");
		int index = Math.max(options.indexOf(current), 0);
		act(ActionType.LINK, 0.0, options.get((index + 1) % options.size()));
	}

	/** Removing takes a second click within three seconds. */
	private void remove() {
		long now = System.currentTimeMillis();
		if (now - removeArmedAt < CONFIRM_MS) {
			removeArmedAt = 0L;
			act(ActionType.REMOVE, 0.0, "");
		} else {
			removeArmedAt = now;
		}
		refreshWidgets();
	}

	private void spawn(boolean white) {
		flush();
		send(new PanelPayloads.Action("", ActionType.SPAWN, white ? 1.0 : 0.0, ""));
	}

	private boolean recentlyEdited(ActionType type) {
		Long at = editedAt.get(type);
		return at != null && System.currentTimeMillis() - at < EDIT_HOLD_MS;
	}

	private void refreshWidgets() {
		if (massSlider == null) {
			return;
		}
		PanelPayloads.Entry entry = selected();
		boolean has = entry != null && allowed;
		for (var widget : List.of(prevButton, selectButton, nextButton)) {
			widget.active = allowed && entries.size() > 1;
		}
		selectButton.active = allowed && !entries.isEmpty();
		for (var widget : List.of(massSlider, spinSlider, temperatureSlider, scaleSlider, pullSlider)) {
			widget.active = has;
		}
		for (var widget : List.of(diskButton, frozenButton, stableButton, teleportButton, moveButton, removeButton)) {
			widget.active = has;
		}
		spawnBlackButton.active = allowed && received;
		spawnWhiteButton.active = allowed && received;
		lensingButton.setMessage(Text.translatable(KEY + (ClientConfig.lensing() ? "lensing.on" : "lensing.off")));

		if (entry == null) {
			selectButton.setMessage(Text.translatable(KEY + (received ? "none" : "loading")));
			diskButton.setMessage(Text.translatable(KEY + "disk", ScreenTexts.EMPTY));
			frozenButton.setMessage(Text.translatable(KEY + "frozen", ScreenTexts.EMPTY));
			stableButton.setMessage(Text.translatable(KEY + "stable", ScreenTexts.EMPTY));
			linkButton.setMessage(Text.translatable(KEY + "link", Text.translatable(KEY + "link.none")));
			linkButton.active = false;
			return;
		}
		BlackHoleData hole = entry.hole();
		selectButton.setMessage(selectionLabel(entry));
		if (!recentlyEdited(ActionType.SET_MASS)) {
			massSlider.show(hole.mass());
		}
		if (!recentlyEdited(ActionType.SET_SPIN)) {
			spinSlider.show(hole.spin());
		}
		if (!recentlyEdited(ActionType.SET_TEMPERATURE)) {
			temperatureSlider.show(hole.temperature());
		}
		if (!recentlyEdited(ActionType.SET_SCALE)) {
			scaleSlider.show(hole.scale());
		}
		if (!recentlyEdited(ActionType.SET_PULL_RADIUS)) {
			pullSlider.show(hole.pullRadius());
		}
		diskButton.setMessage(Text.translatable(KEY + "disk", onOff(hole.accretionDisk())));
		frozenButton.setMessage(Text.translatable(KEY + "frozen", onOff(hole.frozen())));
		stableButton.setMessage(Text.translatable(KEY + "stable", onOff(hole.stable())));
		if (hole.white()) {
			List<String> feeders = new ArrayList<>();
			for (PanelPayloads.Entry other : entries) {
				if (other.hole().link().filter(ref -> ref.id().equals(hole.id())).isPresent()) {
					feeders.add(other.hole().id());
				}
			}
			linkButton.setMessage(Text.translatable(KEY + "fed_by", feeders.isEmpty()
				? Text.translatable(KEY + "link.none") : Text.literal(String.join(", ", feeders))));
			linkButton.active = false;
		} else {
			linkButton.setMessage(Text.translatable(KEY + "link", hole.link().<Text>map(ref -> Text.literal(ref.id()))
				.orElse(Text.translatable(KEY + "link.none"))));
			linkButton.active = allowed;
		}
		boolean armed = System.currentTimeMillis() - removeArmedAt < CONFIRM_MS;
		removeButton.setMessage(Text.translatable(KEY + (armed ? "remove.confirm" : "remove"))
			.formatted(armed ? Formatting.RED : Formatting.RESET));
	}

	private static Text onOff(boolean value) {
		return value ? ScreenTexts.ON : ScreenTexts.OFF;
	}

	private Text selectionLabel(PanelPayloads.Entry entry) {
		BlackHoleData hole = entry.hole();
		MinecraftClient client = MinecraftClient.getInstance();
		String here = client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
		Text where = entry.dimension().equals(here) && client.player != null
			? Text.translatable(KEY + "distance", fmt(hole.pos().distanceTo(client.player.getPos()), 0))
			: Text.literal(entry.dimension());
		return Text.translatable(KEY + "selected", Text.literal(hole.id()).formatted(hole.white() ? Formatting.GOLD : Formatting.LIGHT_PURPLE),
			Text.translatable(KEY + (hole.white() ? "kind.white" : "kind.black")), where);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------------------------------------------------

	/** Y of the status line, under the last row of buttons. */
	private int statusY() {
		return top + 46 + 6 * ROW + HEIGHT + 4;
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		context.fill(left - 6, top - 14, left + panelWidth + 6, statusY() + 12, 0xB0100A1C);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (ticks % 10 == 0) {
			refreshWidgets();
		}
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top - 11, 0xFFD9B8FF);
		int y = top + 24;
		PanelPayloads.Entry entry = selected();
		if (!allowed) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable(KEY + "denied"), width / 2, y + 4, 0xFFFF6060);
		} else if (entry != null) {
			BlackHoleData hole = entry.hole();
			double rs = hole.schwarzschildRadius();
			Text first = Text.translatable(KEY + "info.position", entry.dimension(), fmt(hole.x(), 1), fmt(hole.y(), 1), fmt(hole.z(), 1),
				fmt(rs, 2), fmt(hole.horizonRadius(), 2));
			String state = hole.frozen() ? "frozen" : hole.stable() ? "stable"
				: hole.white() ? (entry.feeding() ? "ejecting" : "gathering") : (entry.feeding() ? "feeding" : "quiescent");
			Text second = Text.translatable(KEY + "info.state", Text.translatable(KEY + "state." + state), fmt(hole.influenceRadius(), 0),
				hole.white() ? Text.translatable(KEY + "info.core") : Text.translatable(KEY + "info.isco",
					fmt(Relativity.iscoRadius(rs, hole.spin()), 2)));
			context.drawTextWithShadow(textRenderer, first, left, y, 0xFFC8D8FF);
			context.drawTextWithShadow(textRenderer, second, left, y + 10, 0xFFA8B8E0);
		} else if (received) {
			context.drawTextWithShadow(textRenderer, Text.translatable(KEY + "empty"), left, y + 4, 0xFFA8B8E0);
		}
		if (!status.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, status, width / 2, statusY(), statusError ? 0xFFFF7070 : 0xFF90FFB0);
		}
	}

	private static String fmt(double value, int decimals) {
		return String.format(Locale.ROOT, "%." + decimals + "f", value);
	}
}
