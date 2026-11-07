package com.prof.blackhole.client.render;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

/**
 * Detects an active Iris shader pack, which replaces vanilla's render pipeline: the lensing pass would then work on a
 * framebuffer the pack is not done with, so the holes are drawn by {@link FallbackHoleRenderer} instead. Uses Iris's
 * public API by reflection, so Iris is not a dependency.
 */
final class ShaderPacks {
	@Nullable
	private static final Object IRIS_API;
	@Nullable
	private static final Method IN_USE;

	static {
		Object api = null;
		Method inUse = null;
		if (FabricLoader.getInstance().isModLoaded("iris")) {
			try {
				Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				api = type.getMethod("getInstance").invoke(null);
				inUse = type.getMethod("isShaderPackInUse");
			} catch (ReflectiveOperationException | LinkageError e) {
				api = null;
				inUse = null;
			}
		}
		IRIS_API = api;
		IN_USE = inUse;
	}

	private ShaderPacks() {
	}

	static boolean inUse() {
		if (IRIS_API == null || IN_USE == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(IN_USE.invoke(IRIS_API));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
