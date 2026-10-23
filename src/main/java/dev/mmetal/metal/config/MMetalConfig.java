package dev.mmetal.metal.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * MMetal configuration, stored at {@code config/mmetal.json}. Reloaded from disk whenever
 * the file's timestamp changes, so you can tweak it while the game is running.
 */
public final class MMetalConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mmetal.json");

	public enum UpscaleMode {
		OFF, SPATIAL, TEMPORAL
	}

	/**
	 * What MMetal does while an Iris shader pack is actively rendering. The pack owns
	 * the look of the world; MMetal's present-time features still compose on top of it.
	 */
	public enum IrisMode {
		/** Passthrough: vanilla presents the pack's frame untouched (legacy behaviour). */
		STAND_DOWN,
		/** The pack owns the look; MMetal adds MetalFX upscale + frame interpolation. */
		METALFX,
		/** The full pipeline (MMetal lighting effects included) runs under the pack too. */
		FULL
	}

	/**
	 * Render-resolution presets, matching the standard upscaler quality ladder
	 * (linear scale factors like DLSS/FSR/XeSS).
	 */
	public enum QualityPreset {
		/** 33% linear (about 11% of the pixels) */
		ULTRA_PERFORMANCE(1.0 / 3.0, "Ultra Performance"),
		/** 50% linear (about 25% of the pixels) */
		PERFORMANCE(0.5, "Performance"),
		/** 58% linear (about 34% of the pixels) */
		BALANCED(0.58, "Balanced"),
		/** 67% linear (about 44% of the pixels) */
		QUALITY(2.0 / 3.0, "Quality"),
		/** Native resolution, no upscaling */
		NONE(1.0, "None");

		public final double scale;
		public final String label;

		QualityPreset(double scale, String label) {
			this.scale = scale;
			this.label = label;
		}

		/** Nearest preset for a legacy free-form render scale. */
		public static QualityPreset fromScale(double scale) {
			QualityPreset best = QUALITY;
			double bestDelta = Double.MAX_VALUE;
			for (QualityPreset preset : values()) {
				double delta = Math.abs(preset.scale - scale);
				if (delta < bestDelta) {
					bestDelta = delta;
					best = preset;
				}
			}
			return best;
		}
	}

	public boolean enabled = true;

	/** Render-resolution tier; the MetalFX upscaler restores full resolution. */
	public QualityPreset qualityPreset = QualityPreset.QUALITY;

	/** Legacy free-form scale (pre-preset configs). Migrated to qualityPreset on load. */
	public double renderScale = 0.75;

	public UpscaleMode upscaleMode = UpscaleMode.SPATIAL;

	/** Behaviour while an Iris shader pack is actively rendering (see {@link IrisMode}). */
	public IrisMode irisMode = IrisMode.METALFX;

	/** MetalFX Frame Interpolation (macOS 26+). Set the game's Max FPS to half your refresh rate. */
	public boolean frameInterpolation = false;

	public Effects effects = new Effects();

	public static class Effects {
		public boolean enabled = true;
		/** Screen-space ray marched sun shadows + ambient occlusion (the "RT lighting" tier). */
		public boolean rayTracing = true;
		public boolean sunShadows = true;
		public boolean ambientOcclusion = true;
		public double sunStrength = 0.55;
		public double aoStrength = 0.45;
		public int marchSteps = 12;
		public double rayLength = 6.0;
		/** Derive the sun direction from the world clock (god rays follow the real sun). */
		public boolean sunFromWorldTime = true;
		public double sunElevationDeg = 55.0;
		public double sunAzimuthDeg = 135.0;
		/** Screen-space volumetric light shafts (god rays) toward the configured sun. */
		public boolean volumetricLight = true;
		public double volumetricStrength = 0.15;
		/** Ray traced reflections (screen space). */
		public boolean reflections = false;
		public double reflectionStrength = 0.35;
		/** Ray traced global illumination / colour bleed (screen space). */
		public boolean globalIllumination = false;
		public double giStrength = 0.35;
		/** Spatial denoise of the ray-marched effects (removes Monte-Carlo grain). */
		public boolean denoise = true;
		/** Hardware ray-traced shadows: BVH of block bounding boxes, traced on the GPU. */
		public boolean hardwareRtShadows = false;
		public int rtShadowRadius = 24;
		/** Screen-space glow around a light-emitting item held in hand. */
		public boolean heldLightGlow = true;
		public double heldLightStrength = 0.6;
		/** Flame position above the first-person item origin (blocks, camera space). */
		public double flameTipHeight = 0.35;
		/** Firelight flicker and flame particles for a light source held in hand. */
		public boolean heldLightFlicker = true;
		public boolean heldLightParticles = true;
		/** Emissive glow: torches, lava, glowstone and the sun bleed light. */
		public boolean bloom = true;
		public double bloomStrength = 0.35;
		/** Camera-motion blur (blends with the previous frame's final image). */
		public boolean motionBlur = false;
		public double motionBlurStrength = 0.3;
		/** ACES filmic tonemap - rolls the sun/highlights off instead of clipping flat white. */
		public boolean tonemap = true;
		public double exposure = 1.0;
	}

	/** Experimental: present through a Metal CAMetalLayer with EDR (true HDR output). */
	public boolean hdrOutput = false;

	/** Look preset id (see {@code dev.mmetal.api.MMetalStyles}); "none" disables it. */
	public String styleId = "none";
	/** Blend amount for the selected look (0 = off, 1 = full). */
	public double styleStrength = 1.0;

	public static MMetalConfig load() {
		try {
			if (Files.exists(PATH)) {
				String json = Files.readString(PATH);
				MMetalConfig parsed = GSON.fromJson(json, MMetalConfig.class);
				if (parsed != null) {
					parsed.migrate();
					return parsed;
				}
			}
		} catch (Exception e) {
			System.err.println("[MMetal] Failed to read " + PATH + ": " + e);
		}
		MMetalConfig fresh = new MMetalConfig();
		fresh.save();
		return fresh;
	}

	/** Fills in fields missing from older config files and keeps values legal. */
	private void migrate() {
		if (effects == null) {
			effects = new Effects();
		}
		if (qualityPreset == null) {
			// Pre-preset config: map the free-form scale onto the nearest tier.
			qualityPreset = QualityPreset.fromScale(renderScale);
		}
		if (upscaleMode == null) {
			upscaleMode = UpscaleMode.SPATIAL;
		}
		if (irisMode == null) {
			irisMode = IrisMode.METALFX;
		}
		if (styleId == null || styleId.isBlank()) {
			styleId = "none";
		} else {
			styleId = styleId.trim().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** Effective linear render scale for this frame. */
	public double scale() {
		QualityPreset preset = qualityPreset != null ? qualityPreset
			: QualityPreset.fromScale(renderScale);
		return preset.scale;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			System.err.println("[MMetal] Failed to write " + PATH + ": " + e);
		}
	}

	public String summary() {
		return String.format(Locale.ROOT,
			"enabled=%s quality=%s upscale=%s iris=%s frameInterpolation=%s effects=%s style=%s",
			enabled, qualityPreset, upscaleMode, irisMode, frameInterpolation, effects.enabled, styleId);
	}
}