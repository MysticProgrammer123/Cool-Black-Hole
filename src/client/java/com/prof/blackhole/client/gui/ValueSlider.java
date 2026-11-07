package com.prof.blackhole.client.gui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * A slider over a physical quantity: the knob position t in [0, 1] maps to a value through a {@link Mapping}
 * (linear, logarithmic, ...). Every change is reported; values arriving from the server are ignored while the knob
 * is held.
 */
final class ValueSlider extends SliderWidget {
	/** Knob position to value and back. */
	interface Mapping {
		double toValue(double t);

		double toSlider(double value);

		static Mapping linear(double min, double max) {
			return new Mapping() {
				@Override
				public double toValue(double t) {
					return min + (max - min) * t;
				}

				@Override
				public double toSlider(double value) {
					return (value - min) / (max - min);
				}
			};
		}

		static Mapping logarithmic(double min, double max) {
			double lo = Math.log(min);
			double hi = Math.log(max);
			return new Mapping() {
				@Override
				public double toValue(double t) {
					return Math.exp(lo + (hi - lo) * t);
				}

				@Override
				public double toSlider(double value) {
					return (Math.log(Math.max(value, min)) - lo) / (hi - lo);
				}
			};
		}

		/** 0 at the far left ("automatic"), then linear from {@code min} to {@code max}. */
		static Mapping autoThenLinear(double min, double max) {
			double gap = 0.02;
			return new Mapping() {
				@Override
				public double toValue(double t) {
					return t < gap ? 0.0 : min + (max - min) * (t - gap) / (1.0 - gap);
				}

				@Override
				public double toSlider(double value) {
					return value <= 0.0 ? 0.0 : gap + (1.0 - gap) * (value - min) / (max - min);
				}
			};
		}
	}

	private final Mapping mapping;
	private final DoubleFunction<Text> label;
	private final DoubleConsumer onChange;
	private boolean held;

	ValueSlider(int x, int y, int width, int height, Mapping mapping, DoubleFunction<Text> label, DoubleConsumer onChange, double initial) {
		super(x, y, width, height, Text.empty(), MathHelper.clamp(mapping.toSlider(initial), 0.0, 1.0));
		this.mapping = mapping;
		this.label = label;
		this.onChange = onChange;
		updateMessage();
	}

	double current() {
		return mapping.toValue(value);
	}

	/** Shows a value from the server, unless the player is dragging the knob. */
	void show(double newValue) {
		if (held) {
			return;
		}
		value = MathHelper.clamp(mapping.toSlider(newValue), 0.0, 1.0);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		if (label != null) {
			setMessage(label.apply(current()));
		}
	}

	@Override
	protected void applyValue() {
		onChange.accept(current());
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		held = true;
		super.onClick(mouseX, mouseY);
	}

	@Override
	public void onRelease(double mouseX, double mouseY) {
		held = false;
		super.onRelease(mouseX, mouseY);
	}
}
