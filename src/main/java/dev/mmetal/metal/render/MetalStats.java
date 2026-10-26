package dev.mmetal.metal.render;

import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.nativebridge.SystemInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live Metal rendering metrics, shown in the F3 debug overlay (Metal-HUD style).
 * Written by the render thread in {@link MetalPipeline}, read by the debug screen.
 */
public final class MetalStats {
	public static volatile String deviceName = "?";
	public static volatile String upscaleMode = "OFF";
	public static volatile String quality = "?";
	/** Render scale chosen by the Dynamic preset (starts at its seed scale). */
	public static volatile float dynamicScale = (float) MMetalConfig.QualityPreset.DYNAMIC.scale;
	public static volatile double pipelineAvgMs;
	public static volatile int renderWidth;
	public static volatile int renderHeight;
	public static volatile int outputWidth;
	public static volatile int outputHeight;

	/** GPU execution time of our command buffer in milliseconds. */
	public static volatile double gpuMs;
	/** CPU time spent encoding the Metal command buffer. */
	public static volatile double encodeMs;
	/** CPU time blocked waiting for the GPU (commit -> completion). */
	public static volatile double waitMs;
	/** Total present-path CPU time (capture + encode + wait). */
	public static volatile double presentMs;

	public static volatile long frames;
	public static volatile long historyResets;

	// Effect state snapshot.
	public static volatile boolean rtLighting;
	public static volatile boolean motionBlur;
	public static volatile boolean reflections;
	public static volatile boolean globalIllumination;
	public static volatile boolean hardwareShadows;
	public static volatile int hardwareShadowPrimitives;
	public static volatile int marchSteps;

	private MetalStats() {
	}

	/** Snapshot of the config-driven effect state, refreshed once per frame. */
	public static void updateConfigSnapshot(MMetalConfig config) {
		if (config == null) {
			return;
		}
		quality = config.qualityPreset == MMetalConfig.QualityPreset.DYNAMIC
			? String.format(Locale.ROOT, "Dynamic (%.0f%%)", dynamicScale * 100.0f)
			: config.qualityPreset != null ? config.qualityPreset.label : "?";
		upscaleMode = config.upscaleMode != null ? config.upscaleMode.name() : "OFF";
		rtLighting = config.effects.enabled && config.effects.rayTracing;
		motionBlur = config.effects.enabled && config.effects.motionBlur;
		reflections = config.effects.enabled && config.effects.reflections;
		globalIllumination = config.effects.enabled && config.effects.globalIllumination;
		hardwareShadows = config.effects.enabled && config.effects.hardwareRtShadows;
		marchSteps = config.effects.marchSteps;
	}

	/** Lines for the F3 overlay (Metal HUD style). */
	public static List<String> lines() {
		List<String> out = new ArrayList<>(6);
		out.add("[MMetal] " + deviceName + " | MetalFX " + upscaleMode);
		out.add(String.format(Locale.ROOT, "[MMetal] Quality: %s | Render: %dx%d -> %dx%d (%.0f%% pixels)",
			quality, renderWidth, renderHeight, outputWidth, outputHeight, pixelPercentage()));
		out.add(String.format(Locale.ROOT, "[MMetal] GPU: %.2f ms | Encode: %.2f ms | Sync wait: %.2f ms | Present: %.2f ms",
			gpuMs, encodeMs, waitMs, presentMs));
		out.add(String.format(Locale.ROOT,
			"[MMetal] Frames: %d | History resets: %d | Pipeline avg: %.2f ms",
			frames, historyResets, pipelineAvgMs));
		out.add(String.format(Locale.ROOT,
			"[MMetal] RT: shadows/AO %s | reflections %s | GI %s | %d rays",
			rtLighting ? "on" : "off", reflections ? "on" : "off",
			globalIllumination ? "on" : "off", marchSteps));
		out.add(String.format(Locale.ROOT, "[MMetal] HW RT shadows: %s (%d block boxes) | motion blur %s",
			hardwareShadows ? "on" : "off", hardwareShadowPrimitives, motionBlur ? "on" : "off"));
		out.add("[MMetal] Sync: glFinish + waitUntilCompleted | IOSurface BGRA8 zero-copy");
		return out;
	}

	private static double pixelPercentage() {
		if (outputWidth <= 0 || outputHeight <= 0) {
			return 100.0;
		}
		return 100.0 * (double) renderWidth * renderHeight / ((double) outputWidth * outputHeight);
	}

	public static String oneLine() {
		return String.format(Locale.ROOT,
			"%s | %s | %s | %dx%d -> %dx%d | GPU %.2fms | frames %d",
			deviceName, quality, upscaleMode, renderWidth, renderHeight, outputWidth, outputHeight,
			gpuMs, frames);
	}

	static void resetCountersOnResize() {
		// Counters are session-lifetime; nothing to do (kept for symmetry).
	}

	/** True when we're running on a platform where metrics make sense. */
	public static boolean supported() {
		return SystemInfo.isMac();
	}
}