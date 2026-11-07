package com.prof.blackhole.item;

import com.prof.blackhole.data.BlackHoleData;
import java.util.Optional;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * What a Mini Singularity Injector makes, cycled by using it in the air and stored on the stack
 * ({@code minecraft:custom_data}, key {@value #KEY}):
 * <ul>
 *     <li><b>Implosion</b> - a 1 M_J black hole (a* = 0.5) that devours its surroundings and grows;</li>
 *     <li><b>Evaporation</b> - a 0.12 M_J micro hole with a tight pull: it eats little, then evaporates within
 *     a minute or two in a fierce burst of Hawking radiation, leaving a Singularity Core;</li>
 *     <li><b>Stationary</b> - a 1 M_J hole held stable: it bends light and pulls, but neither grows, evaporates
 *     nor eats blocks.</li>
 * </ul>
 */
public enum InjectorMode {
	IMPLOSION("implosion", 1.0, 0.5, 0.0, false, Formatting.LIGHT_PURPLE),
	EVAPORATION("evaporation", 0.12, 0.2, 3.0, false, Formatting.GREEN),
	STATIONARY("stationary", 1.0, 0.5, 0.0, true, Formatting.AQUA);

	public static final String KEY = "InjectorMode";
	private static final InjectorMode[] VALUES = values();

	private final String name;
	private final double mass;
	private final double spin;
	private final double pullRadius;
	private final boolean stable;
	private final Formatting color;

	InjectorMode(String name, double mass, double spin, double pullRadius, boolean stable, Formatting color) {
		this.name = name;
		this.mass = mass;
		this.spin = spin;
		this.pullRadius = pullRadius;
		this.stable = stable;
		this.color = color;
	}

	public String id() {
		return name;
	}

	public double mass() {
		return mass;
	}

	public Formatting color() {
		return color;
	}

	public InjectorMode next() {
		return VALUES[(ordinal() + 1) % VALUES.length];
	}

	public Text displayName() {
		return Text.translatable("item.profs_black_hole.mini_singularity_injector.mode." + name).formatted(color);
	}

	public Text description() {
		return Text.translatable("item.profs_black_hole.mini_singularity_injector.mode." + name + ".description");
	}

	/** The hole this mode collapses into at {@code pos}; its id is assigned when it forms. */
	public BlackHoleData hole(Vec3d pos) {
		return new BlackHoleData("", false, pos.x, pos.y, pos.z, mass, spin, BlackHoleData.DEFAULT_ACCRETION_DISK,
			BlackHoleData.DEFAULT_TEMPERATURE, BlackHoleData.DEFAULT_SCALE, pullRadius, false, stable, -1L, Optional.empty());
	}

	public static InjectorMode of(ItemStack stack) {
		NbtComponent data = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT);
		String name = data.copyNbt().getString(KEY);
		for (InjectorMode mode : VALUES) {
			if (mode.name.equals(name)) {
				return mode;
			}
		}
		return IMPLOSION;
	}

	public void applyTo(ItemStack stack) {
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, (NbtCompound nbt) -> nbt.putString(KEY, name));
	}
}
