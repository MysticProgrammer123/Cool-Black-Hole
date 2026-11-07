package com.prof.blackhole.client.render;

import com.prof.blackhole.item.InjectorMode;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/**
 * The Mini Singularity Injector: a core of the animated End Portal starfield ({@code minecraft:block/end_portal},
 * drawn with the end portal render layer, whose parallax star layers move with the screen) held in a cage whose
 * material shows the selected mode (netherite: Implosion, emerald: Evaporation, diamond: Stationary).
 * The item model ({@code builtin/entity}) supplies the display transforms; this draws in the unit cube.
 */
public final class SingularityInjectorRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
	/** The cage shows the mode: netherite for Implosion, emerald for Evaporation, diamond for Stationary. */
	private static final Identifier[] CAGE_TEXTURES = {
			Identifier.ofVanilla("block/netherite_block"),
			Identifier.ofVanilla("block/emerald_block"),
			Identifier.ofVanilla("block/diamond_block")
	};
	private static final float CORE_MIN = 3.5f / 16.0f;
	private static final float CORE_MAX = 12.5f / 16.0f;
	private static final float CAGE_MIN = 2.0f / 16.0f;
	private static final float CAGE_MAX = 14.0f / 16.0f;
	private static final float BAR = 2.0f / 16.0f;

	@Override
	public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
			int light, int overlay) {
		MatrixStack.Entry entry = matrices.peek();
		Matrix4f matrix = entry.getPositionMatrix();

		VertexConsumer portal = vertexConsumers.getBuffer(RenderLayer.getEndPortal());
		box(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX, (x, y, z, u, v, nx, ny, nz) ->
				portal.vertex(matrix, x, y, z));

		Identifier cageTexture = CAGE_TEXTURES[InjectorMode.of(stack).ordinal() % CAGE_TEXTURES.length];
		Sprite sprite = MinecraftClient.getInstance().getSpriteAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE).apply(cageTexture);
		VertexConsumer cage = vertexConsumers.getBuffer(TexturedRenderLayers.getEntityCutout());
		Corner emit = (x, y, z, u, v, nx, ny, nz) -> cage.vertex(matrix, x, y, z).color(-1)
				.texture(sprite.getFrameU(u), sprite.getFrameV(v)).overlay(overlay).light(light).normal(entry, nx, ny, nz);
		float lo = CAGE_MIN;
		float hi = CAGE_MAX;
		float loIn = CAGE_MIN + BAR;
		float hiIn = CAGE_MAX - BAR;
		// Four bars along each axis, at the corners of the cage.
		for (int i = 0; i < 4; i++) {
			float a0 = (i & 1) == 0 ? lo : hiIn;
			float b0 = (i & 2) == 0 ? lo : hiIn;
			box(lo, a0, b0, hi, a0 + BAR, b0 + BAR, emit);
			box(a0, lo, b0, a0 + BAR, hi, b0 + BAR, emit);
			box(a0, b0, lo, a0 + BAR, b0 + BAR, hi, emit);
		}
	}

	@FunctionalInterface
	private interface Corner {
		void emit(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	/**
	 * Emits the six faces of an axis-aligned box, each counter-clockwise seen from outside, with UVs from the face's
	 * own extent (so the cage texture is not stretched along the bars).
	 */
	private static void box(float x0, float y0, float z0, float x1, float y1, float z1, Corner c) {
		// Down (-Y) and up (+Y).
		quad(c, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, x0, z0, x1, z1);
		quad(c, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, z0, x1, z1);
		// North (-Z) and south (+Z).
		quad(c, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, x1, y1);
		quad(c, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, x1, y1);
		// West (-X) and east (+X).
		quad(c, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, z0, y0, z1, y1);
		quad(c, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, z0, y0, z1, y1);
	}

	/** One quad; (u0, v0)-(u1, v1) is the face's extent in its two in-plane coordinates, used as texture coordinates. */
	private static void quad(Corner c, float nx, float ny, float nz,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
			float u0, float v0, float u1, float v1) {
		c.emit(ax, ay, az, u0, v0, nx, ny, nz);
		c.emit(bx, by, bz, u1, v0, nx, ny, nz);
		c.emit(cx, cy, cz, u1, v1, nx, ny, nz);
		c.emit(dx, dy, dz, u0, v1, nx, ny, nz);
	}
}
