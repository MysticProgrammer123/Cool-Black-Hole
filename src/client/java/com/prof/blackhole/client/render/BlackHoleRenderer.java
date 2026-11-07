package com.prof.blackhole.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.prof.blackhole.ProfsBlackHole;
import com.prof.blackhole.client.ClientBlackHoleState;
import com.prof.blackhole.client.ClientConfig;
import com.prof.blackhole.data.BlackHoleData;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlProgramManager;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Screen-space gravitational lensing pass, one per visible hole.
 * <p>
 * After the world has been drawn (before the hand), for each hole, farthest first, the main framebuffer's colour
 * and depth are copied into a private framebuffer and {@code black_hole.fsh} is run as a full-screen triangle back
 * into the main framebuffer, sampling the copy, so every nearer hole lenses the image of the ones behind it. The
 * program is compiled with raw GL through {@link GlStateManager} so it is independent of the vanilla post-effect
 * JSON pipeline; every piece of GL state we touch is saved and restored through the same caches Minecraft uses, so
 * vanilla rendering continues unaffected.
 * <p>
 * When the pass cannot run (the shader failed to build, the player switched lensing off, or a shader pack has
 * taken over the pipeline), {@link FallbackHoleRenderer} draws the holes as plain geometry instead.
 */
public final class BlackHoleRenderer implements SimpleSynchronousResourceReloadListener {
	public static final BlackHoleRenderer INSTANCE = new BlackHoleRenderer();

	private static final Identifier RELOAD_ID = ProfsBlackHole.id("black_hole_renderer");
	private static final Identifier VERTEX_SHADER = ProfsBlackHole.id("shaders/program/black_hole.vsh");
	private static final Identifier FRAGMENT_SHADER = ProfsBlackHole.id("shaders/program/black_hole.fsh");
	private static final int INFO_LOG_LENGTH = 32768;

	/** True while WorldRenderer.render is executing; lets the entity mixin ignore GUI entity rendering. */
	private static boolean worldPass;

	private int program;
	private int vao;
	private boolean disabled = true;
	private SimpleFramebuffer sceneCopy;

	/** First texture unit of the extra depth layers, in shader order: translucent, item entity, particles, clouds. */
	private static final int EXTRA_DEPTH_UNIT = 2;
	private static final int EXTRA_DEPTH_COUNT = 4;
	/** Largest plausible distance of the eye from the camera position (view bobbing), blocks. */
	private static final float MAX_EYE_OFFSET = 2.0f;

	private int uSceneColor = -1, uSceneDepth = -1;
	private int uTranslucentDepth = -1, uItemEntityDepth = -1, uParticlesDepth = -1, uCloudsDepth = -1;
	private int uProjection = -1, uInvProjection = -1, uView = -1, uInvView = -1;
	private int uBlackHolePos = -1, uBlackHoleWorldPos = -1, uEyeOffset = -1;
	private int uRs = -1, uSpin = -1, uDiskEnabled = -1, uDiskTemperature = -1;
	private int uTime = -1, uScreenSize = -1, uAspect = -1, uInfluenceRadius = -1;
	private int uLightCount = -1, uLights = -1, uLightColors = -1;
	private int uWhite = -1, uGoggles = -1;

	private BlackHoleRenderer() {
	}

	public static void beginWorldPass() {
		worldPass = true;
	}

	public static void endWorldPass() {
		worldPass = false;
	}

	public static boolean isWorldPass() {
		return worldPass;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Resource reload / shader compilation
	// ------------------------------------------------------------------------------------------------------------

	@Override
	public Identifier getFabricId() {
		return RELOAD_ID;
	}

	@Override
	public void reload(ResourceManager manager) {
		final String vertexSource;
		final String fragmentSource;
		try {
			vertexSource = readSource(manager, VERTEX_SHADER);
			fragmentSource = readSource(manager, FRAGMENT_SHADER);
		} catch (IOException e) {
			ProfsBlackHole.LOGGER.error("Black hole shader sources could not be loaded; lensing disabled", e);
			if (RenderSystem.isOnRenderThread()) {
				deleteProgram();
			} else {
				RenderSystem.recordRenderCall(this::deleteProgram);
			}
			return;
		}

		// Synchronous reloaders apply on the client thread, which is the render thread; stay safe regardless.
		if (RenderSystem.isOnRenderThread()) {
			compile(vertexSource, fragmentSource);
		} else {
			RenderSystem.recordRenderCall(() -> compile(vertexSource, fragmentSource));
		}
	}

	private static String readSource(ResourceManager manager, Identifier id) throws IOException {
		Resource resource = manager.getResource(id).orElseThrow(() -> new IOException("Missing resource " + id));
		try (InputStream in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void compile(String vertexSource, String fragmentSource) {
		deleteProgram();
		int vs = 0;
		int fs = 0;
		int prog = 0;
		try {
			vs = compileShader(GL20.GL_VERTEX_SHADER, vertexSource, VERTEX_SHADER);
			fs = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource, FRAGMENT_SHADER);

			prog = GlStateManager.glCreateProgram();
			if (prog <= 0) {
				throw new ShaderBuildException("glCreateProgram returned " + prog);
			}
			GlStateManager.glAttachShader(prog, vs);
			GlStateManager.glAttachShader(prog, fs);
			GlStateManager.glLinkProgram(prog);
			if (GlStateManager.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				throw new ShaderBuildException("Link failed:\n" + GlStateManager.glGetProgramInfoLog(prog, INFO_LOG_LENGTH));
			}

			program = prog;
			prog = 0;
			lookupUniforms();
			if (vao == 0) {
				vao = GlStateManager._glGenVertexArrays();
			}
			disabled = false;
			ProfsBlackHole.LOGGER.info("Black hole shader compiled");
		} catch (Throwable t) {
			disabled = true;
			if (program > 0) {
				GlStateManager.glDeleteProgram(program);
			}
			program = 0;
			if (t instanceof ShaderBuildException) {
				ProfsBlackHole.LOGGER.error("Black hole shader failed to build; lensing disabled. {}", t.getMessage());
			} else {
				ProfsBlackHole.LOGGER.error("Black hole shader failed to build; lensing disabled", t);
			}
		} finally {
			if (prog > 0) {
				GlStateManager.glDeleteProgram(prog);
			}
			// Shaders attached to a live program are only flagged for deletion, so this is safe after linking.
			if (vs > 0) {
				GlStateManager.glDeleteShader(vs);
			}
			if (fs > 0) {
				GlStateManager.glDeleteShader(fs);
			}
		}
	}

	private static int compileShader(int type, String source, Identifier id) {
		int shader = GlStateManager.glCreateShader(type);
		GlStateManager.glShaderSource(shader, List.of(source));
		GlStateManager.glCompileShader(shader);
		if (GlStateManager.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			String log = GlStateManager.glGetShaderInfoLog(shader, INFO_LOG_LENGTH);
			GlStateManager.glDeleteShader(shader);
			throw new ShaderBuildException("Compile failed for " + id + ":\n" + log);
		}
		return shader;
	}

	private void lookupUniforms() {
		uSceneColor = location("uSceneColor");
		uSceneDepth = location("uSceneDepth");
		uTranslucentDepth = location("uTranslucentDepth");
		uItemEntityDepth = location("uItemEntityDepth");
		uParticlesDepth = location("uParticlesDepth");
		uCloudsDepth = location("uCloudsDepth");
		uProjection = location("uProjection");
		uInvProjection = location("uInvProjection");
		uView = location("uView");
		uInvView = location("uInvView");
		uBlackHolePos = location("uBlackHolePos");
		uBlackHoleWorldPos = location("uBlackHoleWorldPos");
		uEyeOffset = location("uEyeOffset");
		uRs = location("uRs");
		uSpin = location("uSpin");
		uDiskEnabled = location("uDiskEnabled");
		uDiskTemperature = location("uDiskTemperature");
		uTime = location("uTime");
		uScreenSize = location("uScreenSize");
		uAspect = location("uAspect");
		uInfluenceRadius = location("uInfluenceRadius");
		uLightCount = location("uLightCount");
		uLights = location("uLights");
		uLightColors = location("uLightColors");
		uWhite = location("uWhite");
		uGoggles = location("uGoggles");
	}

	private int location(String name) {
		int loc = GlStateManager._glGetUniformLocation(program, name);
		if (loc < 0) {
			// Unused uniforms are optimised away by the GLSL compiler; uploading to -1 is a harmless no-op.
			ProfsBlackHole.LOGGER.debug("Black hole shader uniform '{}' is inactive", name);
		}
		return loc;
	}

	private void deleteProgram() {
		if (program > 0) {
			GlStateManager.glDeleteProgram(program);
		}
		program = 0;
		disabled = true;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Per-frame rendering
	// ------------------------------------------------------------------------------------------------------------

	/**
	 * Called at the tail of {@code WorldRenderer.render}. Never throws.
	 *
	 * @param view       view rotation matrix (camera at origin), as passed to WorldRenderer.render
	 * @param projection projection matrix (with view bobbing / nausea), as passed to WorldRenderer.render
	 */
	/** Whether the lensing pass runs: the shader built, the player has it on, and no shader pack replaces the pipeline. */
	public boolean isActive() {
		return !disabled && program != 0 && ClientConfig.lensing() && !ShaderPacks.inUse();
	}

	public void render(Camera camera, Matrix4f view, Matrix4f projection, float tickDelta) {
		if (!isActive()) {
			return;
		}
		List<BlackHoleData> holes = ClientBlackHoleState.all();
		if (holes.isEmpty()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		Vec3d camPos = camera.getPos();
		List<BlackHoleData> passes = visible(holes, camPos, client.options.getClampedViewDistance() * 16.0);
		if (passes.isEmpty()) {
			return;
		}

		try {
			RenderSystem.assertOnRenderThread();
			draw(client, world, passes, camPos, view, projection, tickDelta);
		} catch (Throwable t) {
			disabled = true;
			ProfsBlackHole.LOGGER.error("Black hole renderer failed and has been disabled until the next resource reload", t);
		}
	}

	/**
	 * The holes to lens this frame, farthest first so each nearer pass lenses the image of the ones behind it: the
	 * {@link ClientConfig#maxLensedHoles} that look biggest (r_s over distance), among those whose lensing could
	 * reach the screen.
	 */
	private static List<BlackHoleData> visible(List<BlackHoleData> holes, Vec3d camPos, double renderDistance) {
		record Candidate(BlackHoleData hole, double distance, double size) {
		}
		List<Candidate> candidates = new ArrayList<>();
		for (BlackHoleData hole : holes) {
			double rs = hole.schwarzschildRadius();
			double distance = hole.pos().distanceTo(camPos);
			// The shader traces within max(influence, 16 r_s) (the disk alone reaches 6 r_s), which exceeds the capped
			// influence radius for heavy holes; cull against the same extent so visible lensing is never skipped.
			double visibleExtent = Math.max(hole.influenceRadius(), 16.0 * rs);
			if (distance <= visibleExtent + renderDistance) {
				candidates.add(new Candidate(hole, distance, rs / Math.max(distance, 1.0)));
			}
		}
		candidates.sort(Comparator.comparingDouble(Candidate::size).reversed());
		List<Candidate> chosen = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), ClientConfig.maxLensedHoles())));
		chosen.sort(Comparator.comparingDouble(Candidate::distance).reversed());
		List<BlackHoleData> result = new ArrayList<>(chosen.size());
		for (Candidate candidate : chosen) {
			result.add(candidate.hole());
		}
		return result;
	}

	private void draw(MinecraftClient client, ClientWorld world, List<BlackHoleData> passes, Vec3d camPos, Matrix4f view,
			Matrix4f projection, float tickDelta) {
		Framebuffer main = client.getFramebuffer();
		int width = main.textureWidth;
		int height = main.textureHeight;
		if (width <= 0 || height <= 0 || main.fbo < 0) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// ---- save state -----------------------------------------------------------------------------------
			int prevProgram = GlStateManager._getInteger(GL20.GL_CURRENT_PROGRAM);
			int prevVao = GlStateManager._getInteger(GL30.GL_VERTEX_ARRAY_BINDING);
			int prevDrawFb = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
			int prevReadFb = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
			int prevActiveTexture = GlStateManager._getActiveTexture();
			// Units 0 (colour), 1 (depth) and the extra depth layers; unit 0 is left active for the copy below.
			int[] prevTextures = new int[EXTRA_DEPTH_UNIT + EXTRA_DEPTH_COUNT];
			for (int unit = prevTextures.length - 1; unit >= 0; unit--) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
				prevTextures[unit] = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
			}
			boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
			boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
			boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
			boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
			boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
			ByteBuffer colorMask = stack.malloc(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
			int prevViewportX = GlStateManager.Viewport.getX();
			int prevViewportY = GlStateManager.Viewport.getY();
			int prevViewportW = GlStateManager.Viewport.getWidth();
			int prevViewportH = GlStateManager.Viewport.getHeight();

			try {
				// ---- copy scene colour + depth ------------------------------------------------------------------
				// (Re)allocation binds textures on the active unit (unit 0 here, restored below) and toggles cached
				// depth-test / viewport state, all of which is restored at the end.
				SimpleFramebuffer copy = ensureSceneCopy(width, height);

				// Scissor also clips glBlitFramebuffer, so set up fixed-function state before the copy.
				GlStateManager._disableScissorTest();
				GlStateManager._disableBlend();
				GlStateManager._disableDepthTest();
				GlStateManager._disableCull();
				GlStateManager._depthMask(false);
				GlStateManager._colorMask(true, true, true, true);
				boolean goggles = GogglesVision.isWorn(client);

				for (BlackHoleData hole : passes) {
					// Each pass lenses the current image, including the holes already drawn behind this one.
					GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
					GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.fbo);
					GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
							GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

					// ---- draw full-screen triangle into the main framebuffer ------------------------------------
					GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.fbo);
					GlStateManager._viewport(0, 0, width, height);

					GlStateManager._activeTexture(GL13.GL_TEXTURE0);
					GlStateManager._bindTexture(copy.getColorAttachment());
					GlStateManager._activeTexture(GL13.GL_TEXTURE1);
					GlStateManager._bindTexture(copy.getDepthAttachment());
					int[] extraDepth = extraDepthTextures(client, copy);
					for (int i = 0; i < EXTRA_DEPTH_COUNT; i++) {
						GlStateManager._activeTexture(GL13.GL_TEXTURE0 + EXTRA_DEPTH_UNIT + i);
						GlStateManager._bindTexture(extraDepth[i]);
					}

					GlProgramManager.useProgram(program);
					uploadUniforms(stack, world, hole, camPos, view, projection, tickDelta, width, height, goggles);

					// The VAO cached by BufferRenderer is about to change; drop its cache so the next vanilla draw rebinds.
					BufferRenderer.resetCurrentVertexBuffer();
					GlStateManager._glBindVertexArray(vao);
					GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
				}
			} finally {
				// ---- restore state ------------------------------------------------------------------------------
				GlStateManager._glBindVertexArray(prevVao);
				BufferRenderer.resetCurrentVertexBuffer();
				GlProgramManager.useProgram(prevProgram);

				for (int unit = prevTextures.length - 1; unit >= 0; unit--) {
					GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
					GlStateManager._bindTexture(prevTextures[unit]);
				}
				GlStateManager._activeTexture(prevActiveTexture);

				if (prevDrawFb == prevReadFb) {
					GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevDrawFb);
				} else {
					GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFb);
					GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFb);
				}
				GlStateManager._viewport(prevViewportX, prevViewportY, prevViewportW, prevViewportH);

				setCap(prevBlend, GlStateManager::_enableBlend, GlStateManager::_disableBlend);
				setCap(prevDepthTest, GlStateManager::_enableDepthTest, GlStateManager::_disableDepthTest);
				setCap(prevCull, GlStateManager::_enableCull, GlStateManager::_disableCull);
				setCap(prevScissor, GlStateManager::_enableScissorTest, GlStateManager::_disableScissorTest);
				GlStateManager._depthMask(prevDepthMask);
				GlStateManager._colorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
			}
		}
	}

	/**
	 * Depth textures of the layers Fabulous graphics keeps out of the main framebuffer (water and other translucent
	 * terrain, item entities, particles, clouds), in shader order. A layer that does not exist (Fast / Fancy draw
	 * everything into the main framebuffer) or was not drawn this frame falls back to the main depth copy, which
	 * leaves the shader's minimum unchanged.
	 */
	private static int[] extraDepthTextures(MinecraftClient client, SimpleFramebuffer copy) {
		WorldRenderer worldRenderer = client.worldRenderer;
		// The clouds target is only cleared while clouds render; when they are off it would hold a stale frame.
		boolean clouds = client.options.getCloudRenderModeValue() != CloudRenderMode.OFF;
		Framebuffer[] layers = {
				worldRenderer.getTranslucentFramebuffer(),
				worldRenderer.getEntityFramebuffer(),
				worldRenderer.getParticlesFramebuffer(),
				clouds ? worldRenderer.getCloudsFramebuffer() : null
		};
		int[] textures = new int[EXTRA_DEPTH_COUNT];
		for (int i = 0; i < EXTRA_DEPTH_COUNT; i++) {
			Framebuffer layer = layers[i];
			int texture = layer != null && layer.useDepthAttachment ? layer.getDepthAttachment() : -1;
			textures[i] = texture > 0 ? texture : copy.getDepthAttachment();
		}
		return textures;
	}

	private static void setCap(boolean enabled, Runnable enable, Runnable disable) {
		(enabled ? enable : disable).run();
	}

	private SimpleFramebuffer ensureSceneCopy(int width, int height) {
		if (sceneCopy == null) {
			sceneCopy = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
			sceneCopy.setTexFilter(GL11.GL_LINEAR);
		} else if (sceneCopy.textureWidth != width || sceneCopy.textureHeight != height) {
			sceneCopy.resize(width, height, MinecraftClient.IS_SYSTEM_MAC);
			// initFbo resets the colour filter to NEAREST; lensed lookups are sub-pixel, so filter linearly.
			sceneCopy.setTexFilter(GL11.GL_LINEAR);
		}
		return sceneCopy;
	}

	private void uploadUniforms(MemoryStack stack, ClientWorld world, BlackHoleData hole, Vec3d camPos, Matrix4f view,
			Matrix4f projection, float tickDelta, int width, int height, boolean goggles) {
		double rs = hole.schwarzschildRadius();
		double influence = hole.influenceRadius();
		Vec3d holePos = hole.pos();
		FloatBuffer matrix = stack.mallocFloat(16);

		GL20.glUniform1i(uSceneColor, 0);
		GL20.glUniform1i(uSceneDepth, 1);
		GL20.glUniform1i(uTranslucentDepth, EXTRA_DEPTH_UNIT);
		GL20.glUniform1i(uItemEntityDepth, EXTRA_DEPTH_UNIT + 1);
		GL20.glUniform1i(uParticlesDepth, EXTRA_DEPTH_UNIT + 2);
		GL20.glUniform1i(uCloudsDepth, EXTRA_DEPTH_UNIT + 3);

		Matrix4f invProjection = new Matrix4f(projection).invert();
		Matrix4f invView = new Matrix4f(view).invert();
		uploadMatrix(uProjection, projection, matrix);
		uploadMatrix(uInvProjection, invProjection, matrix);
		uploadMatrix(uView, view, matrix);
		uploadMatrix(uInvView, invView, matrix);
		Vector3f eye = eyeOffset(invProjection, invView);
		GL20.glUniform3f(uEyeOffset, eye.x, eye.y, eye.z);

		// Subtract in double precision first: absolute world coordinates lose precision as floats far from 0,0.
		GL20.glUniform3f(uBlackHolePos,
				(float) (holePos.x - camPos.x), (float) (holePos.y - camPos.y), (float) (holePos.z - camPos.z));
		GL20.glUniform3f(uBlackHoleWorldPos, (float) holePos.x, (float) holePos.y, (float) holePos.z);

		GL20.glUniform1f(uRs, (float) rs);
		GL20.glUniform1f(uSpin, (float) hole.spin());
		GL20.glUniform1i(uDiskEnabled, hole.accretionDisk() ? 1 : 0);
		GL20.glUniform1f(uDiskTemperature, (float) hole.temperature());
		// A frozen hole's disk stands still: its clock stopped when it was frozen.
		GL20.glUniform1f(uTime, ClientBlackHoleState.shaderTime(hole, ((world.getTime() % 24000L) + tickDelta) / 20.0f));
		GL20.glUniform2f(uScreenSize, width, height);
		GL20.glUniform1f(uAspect, (float) width / (float) height);
		GL20.glUniform1f(uInfluenceRadius, (float) influence);
		GL20.glUniform1i(uWhite, hole.white() ? 1 : 0);
		GL20.glUniform1i(uGoggles, goggles ? 1 : 0);

		List<LightSources.Light> lights = LightSources.get(hole.id());
		int count = Math.min(lights.size(), LightSources.MAX_LIGHTS);
		GL20.glUniform1i(uLightCount, count);
		if (count > 0 && uLights >= 0 && uLightColors >= 0) {
			FloatBuffer positions = stack.mallocFloat(count * 4);
			FloatBuffer colors = stack.mallocFloat(count * 3);
			for (int i = 0; i < count; i++) {
				LightSources.Light light = lights.get(i);
				positions.put((float) (light.x() - camPos.x)).put((float) (light.y() - camPos.y))
						.put((float) (light.z() - camPos.z)).put(light.radius());
				colors.put(light.red()).put(light.green()).put(light.blue());
			}
			GL20.glUniform4fv(uLights, positions.flip());
			GL20.glUniform3fv(uLightColors, colors.flip());
		}
	}

	/**
	 * The true eye (centre of projection) relative to the camera, in world axes. Vanilla multiplies the view-bobbing
	 * (and hurt-tilt) transform into the projection matrix, translation included, so the eye moves off the view-space
	 * origin by up to ~0.1 block while walking. The centre of projection is the point the projection maps to
	 * clip-space (0, 0, 1, 0) (w = 0, on the axis); a projective point, so it is dehomogenised.
	 */
	private static Vector3f eyeOffset(Matrix4f invProjection, Matrix4f invView) {
		Vector4f eye = invProjection.transform(new Vector4f(0.0f, 0.0f, 1.0f, 0.0f));
		if (!(Math.abs(eye.w) > 1.0e-12f)) {
			return new Vector3f();
		}
		Vector3f offset = invView.transformDirection(new Vector3f(eye.x / eye.w, eye.y / eye.w, eye.z / eye.w));
		// Bobbing moves the eye by a fraction of a block; anything else is a degenerate matrix, not an eye.
		return offset.isFinite() && offset.lengthSquared() < MAX_EYE_OFFSET * MAX_EYE_OFFSET ? offset : new Vector3f();
	}

	private static void uploadMatrix(int location, Matrix4f m, FloatBuffer buffer) {
		if (location < 0) {
			return;
		}
		m.get(buffer); // column-major, does not move the buffer position
		GlStateManager._glUniformMatrix4(location, false, buffer);
	}

	private static final class ShaderBuildException extends RuntimeException {
		ShaderBuildException(String message) {
			super(message);
		}
	}
}
