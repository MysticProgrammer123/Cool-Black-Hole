package com.prof.blackhole.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Immediate-mode world geometry drawn after the world (from {@code WorldRendererMixin}): camera-relative positions
 * in world axes, transformed by the view rotation. Two layers: {@link #SOLID} (opaque, writes depth: the shadow of a
 * fallback hole) and {@link #GLOW} (additive, depth-tested but not written: light, field lines, rifts), both
 * unlit, untextured and drawn into the main framebuffer whatever the graphics mode.
 */
public final class HoleGeometry {
	public static final RenderLayer SOLID = RenderLayer.of("profs_black_hole_solid", VertexFormats.POSITION_COLOR,
		VertexFormat.DrawMode.QUADS, 8192, false, false, RenderLayer.MultiPhaseParameters.builder()
			.program(RenderPhase.COLOR_PROGRAM)
			.transparency(RenderPhase.NO_TRANSPARENCY)
			.cull(RenderPhase.DISABLE_CULLING)
			.build(false));
	public static final RenderLayer GLOW = RenderLayer.of("profs_black_hole_glow", VertexFormats.POSITION_COLOR,
		VertexFormat.DrawMode.QUADS, 16384, false, true, RenderLayer.MultiPhaseParameters.builder()
			.program(RenderPhase.COLOR_PROGRAM)
			.transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
			.writeMaskState(RenderPhase.COLOR_MASK)
			.cull(RenderPhase.DISABLE_CULLING)
			.build(false));

	private HoleGeometry() {
	}

	/**
	 * Runs {@code draw} with the view rotation as the position matrix and an identity model-view, then flushes
	 * {@code layer}.
	 */
	public static void draw(Matrix4f viewRotation, RenderLayer layer, Consumer<Builder> draw) {
		VertexConsumerProvider.Immediate immediate = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.identity();
		RenderSystem.applyModelViewMatrix();
		try {
			draw.accept(new Builder(immediate.getBuffer(layer), new Matrix4f(viewRotation)));
			immediate.draw(layer);
		} finally {
			modelView.popMatrix();
			RenderSystem.applyModelViewMatrix();
		}
	}

	/** Emits primitives into one layer. All positions are relative to the camera. */
	public static final class Builder {
		private final VertexConsumer consumer;
		private final Matrix4f matrix;

		Builder(VertexConsumer consumer, Matrix4f matrix) {
			this.consumer = consumer;
			this.matrix = matrix;
		}

		public void vertex(float x, float y, float z, float r, float g, float b, float a) {
			consumer.vertex(matrix, x, y, z).color(r, g, b, a);
		}

		/**
		 * A camera-facing strip from {@code a} to {@code b} of the given width, with its colour at each end.
		 */
		public void ribbon(Vector3f a, Vector3f b, float width, float[] colorA, float alphaA, float[] colorB, float alphaB) {
			Vector3f dir = new Vector3f(b).sub(a);
			Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
			Vector3f side = dir.cross(mid, new Vector3f());
			float length = side.length();
			if (!(length > 1.0e-6f)) {
				return;
			}
			side.mul(0.5f * width / length);
			vertex(a.x + side.x, a.y + side.y, a.z + side.z, colorA[0], colorA[1], colorA[2], alphaA);
			vertex(a.x - side.x, a.y - side.y, a.z - side.z, colorA[0], colorA[1], colorA[2], alphaA);
			vertex(b.x - side.x, b.y - side.y, b.z - side.z, colorB[0], colorB[1], colorB[2], alphaB);
			vertex(b.x + side.x, b.y + side.y, b.z + side.z, colorB[0], colorB[1], colorB[2], alphaB);
		}

		/** A UV sphere of one colour. */
		public void sphere(float cx, float cy, float cz, float radius, float r, float g, float b, float a) {
			int stacks = 14;
			int slices = 24;
			for (int i = 0; i < stacks; i++) {
				double t0 = Math.PI * i / stacks;
				double t1 = Math.PI * (i + 1) / stacks;
				for (int j = 0; j < slices; j++) {
					double p0 = 2.0 * Math.PI * j / slices;
					double p1 = 2.0 * Math.PI * (j + 1) / slices;
					spherePoint(cx, cy, cz, radius, t0, p0, r, g, b, a);
					spherePoint(cx, cy, cz, radius, t1, p0, r, g, b, a);
					spherePoint(cx, cy, cz, radius, t1, p1, r, g, b, a);
					spherePoint(cx, cy, cz, radius, t0, p1, r, g, b, a);
				}
			}
		}

		private void spherePoint(float cx, float cy, float cz, float radius, double theta, double phi, float r, float g, float b, float a) {
			float s = (float) Math.sin(theta);
			vertex(cx + radius * s * (float) Math.cos(phi), cy + radius * (float) Math.cos(theta), cz + radius * s * (float) Math.sin(phi),
				r, g, b, a);
		}

		/**
		 * A ring facing the camera between radii {@code inner} and {@code outer} around {@code center}, its colour
		 * and alpha going from the inner edge's to the outer edge's.
		 */
		public void billboardRing(Vector3f center, float inner, float outer, float[] colorIn, float alphaIn, float[] colorOut,
				float alphaOut) {
			Vector3f n = new Vector3f(center);
			if (!(n.lengthSquared() > 1.0e-8f)) {
				return;
			}
			n.normalize();
			Vector3f helper = Math.abs(n.y) < 0.95f ? new Vector3f(0.0f, 1.0f, 0.0f) : new Vector3f(1.0f, 0.0f, 0.0f);
			Vector3f u = n.cross(helper, new Vector3f()).normalize();
			Vector3f v = u.cross(n, new Vector3f()).normalize();
			int segments = 48;
			for (int i = 0; i < segments; i++) {
				double a0 = 2.0 * Math.PI * i / segments;
				double a1 = 2.0 * Math.PI * (i + 1) / segments;
				float c0 = (float) Math.cos(a0);
				float s0 = (float) Math.sin(a0);
				float c1 = (float) Math.cos(a1);
				float s1 = (float) Math.sin(a1);
				ringVertex(center, u, v, inner, c0, s0, colorIn, alphaIn);
				ringVertex(center, u, v, outer, c0, s0, colorOut, alphaOut);
				ringVertex(center, u, v, outer, c1, s1, colorOut, alphaOut);
				ringVertex(center, u, v, inner, c1, s1, colorIn, alphaIn);
			}
		}

		private void ringVertex(Vector3f center, Vector3f u, Vector3f v, float radius, float c, float s, float[] color, float alpha) {
			vertex(center.x + radius * (u.x * c + v.x * s), center.y + radius * (u.y * c + v.y * s), center.z + radius * (u.z * c + v.z * s),
				color[0], color[1], color[2], alpha);
		}
	}
}
