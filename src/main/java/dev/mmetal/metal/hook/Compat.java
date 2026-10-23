package dev.mmetal.metal.hook;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Soft compatibility with Sodium and Iris. MMetal hooks below both mods (at the
 * swapchain present level), so they coexist naturally; the only behavioral change is
 * that while an Iris shader pack is actively rendering, MMetal passes the frame through
 * untouched instead of re-interpreting the pack's output.
 */
public final class Compat {
	private static final boolean IRIS_PRESENT = FabricLoader.getInstance().isModLoaded("iris");
	private static final boolean SODIUM_PRESENT = FabricLoader.getInstance().isModLoaded("sodium");
	private static Method irisShadersInUse;
	private static Object irisApi;
	private static boolean irisProbed;

	private Compat() {
	}

	public static boolean isIrisPresent() {
		return IRIS_PRESENT;
	}

	public static boolean isSodiumPresent() {
		return SODIUM_PRESENT;
	}

	/** True when an Iris shader pack is currently rendering the frame. */
	public static boolean irisShadersActive() {
		if (!IRIS_PRESENT) {
			return false;
		}
		if (!irisProbed) {
			irisProbed = true;
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object instance = api.getMethod("getInstance").invoke(null);
				irisApi = instance;
				irisShadersInUse = api.getMethod("isShaderPackInUse");
			} catch (Throwable t) {
				irisApi = null;
				irisShadersInUse = null;
			}
		}
		try {
			return irisShadersInUse != null && irisApi != null
				&& (Boolean) irisShadersInUse.invoke(irisApi);
		} catch (Throwable t) {
			return false;
		}
	}
}