package dev.mmetal.metal.ui;

import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.nativebridge.MetalFx;
import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.SystemInfo;
import dev.mmetal.metal.render.MetalPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * In-game configuration screen for MMetal (three columns: rendering, ray tracing,
 * post effects). Open with O, the Options-screen button, or {@code /mmetal ui}.
 */
public final class MMetalConfigScreen extends Screen {
	private static final int W = 150;
	private static final int H = 18;
	private static final int GAP = 6;
	private static final int ROW = 21;

	private final MMetalConfig config = MetalPipeline.config();

	public MMetalConfigScreen() {
		super(Component.literal("MMetal — Metal 4 / MetalFX"));
	}

	@Override
	protected void init() {
		int totalWidth = W * 3 + GAP * 2;
		int x0 = (this.width - totalWidth) / 2;
		int y = 36;
		int yEnd = this.height - 30;

		// ---------------- Column 1: rendering ----------------
		int col = x0;
		int row = 0;
		toggle(col, y, row++, "MMetal", () -> config.enabled, v -> config.enabled = v);
		qualityButton(col, y + ROW * row++);
		upscaleButton(col, y + ROW * row++);
		toggle(col, y, row++, "Frame Interpolation", () -> config.frameInterpolation,
			v -> config.frameInterpolation = v);
		toggle(col, y, row++, "Post Processing", () -> config.effects.enabled,
			v -> config.effects.enabled = v);
		toggle(col, y, row++, "Filmic Tonemap", () -> config.effects.tonemap,
			v -> config.effects.tonemap = v);
		slider(col, y, row++, "Exposure", config.effects.exposure, 0.25, 3.0,
			v -> config.effects.exposure = v, "x");
		toggle(col, y, row++, "HDR Output (EDR)", () -> config.hdrOutput, v -> config.hdrOutput = v);
		toggle(col, y, row++, "Native-Res UI", () -> config.nativeUi, v -> config.nativeUi = v);
		toggle(col, y, row++, "Sun From World Time", () -> config.effects.sunFromWorldTime,
			v -> config.effects.sunFromWorldTime = v);

		// ---------------- Column 2: ray tracing ----------------
		col = x0 + W + GAP;
		row = 0;
		toggle(col, y, row++, "Ray Traced Lighting", () -> config.effects.rayTracing,
			v -> config.effects.rayTracing = v);
		toggle(col, y, row++, "RT Shadows", () -> config.effects.sunShadows,
			v -> config.effects.sunShadows = v);
		slider(col, y, row++, "Shadow Strength", config.effects.sunStrength, 0.0, 1.0,
			v -> config.effects.sunStrength = v, "%");
		toggle(col, y, row++, "RT Ambient Occlusion", () -> config.effects.ambientOcclusion,
			v -> config.effects.ambientOcclusion = v);
		slider(col, y, row++, "AO Strength", config.effects.aoStrength, 0.0, 1.0,
			v -> config.effects.aoStrength = v, "%");
		toggle(col, y, row++, "RT Reflections", () -> config.effects.reflections,
			v -> config.effects.reflections = v);
		slider(col, y, row++, "Reflection Strength", config.effects.reflectionStrength, 0.0, 1.0,
			v -> config.effects.reflectionStrength = v, "%");
		toggle(col, y, row++, "RT Global Illumination", () -> config.effects.globalIllumination,
			v -> config.effects.globalIllumination = v);
		slider(col, y, row++, "GI Strength", config.effects.giStrength, 0.0, 1.0,
			v -> config.effects.giStrength = v, "%");
		toggle(col, y, row++, "RT Shadows (Hardware)", () -> config.effects.hardwareRtShadows,
			v -> config.effects.hardwareRtShadows = v);
		toggle(col, y, row++, "Held Light Glow", () -> config.effects.heldLightGlow,
			v -> config.effects.heldLightGlow = v);
		slider(col, y, row++, "Held Light Strength", config.effects.heldLightStrength, 0.0, 1.0,
			v -> config.effects.heldLightStrength = v, "%");
		slider(col, y, row++, "Flame Height", config.effects.flameTipHeight, 0.0, 0.8,
			v -> config.effects.flameTipHeight = v, "m");
		toggle(col, y, row++, "Firelight Flicker", () -> config.effects.heldLightFlicker,
			v -> config.effects.heldLightFlicker = v);
		toggle(col, y, row++, "Flame Particles", () -> config.effects.heldLightParticles,
			v -> config.effects.heldLightParticles = v);

		// ---------------- Column 3: effects quality ----------------
		col = x0 + (W + GAP) * 2;
		row = 0;
		rayCountButton(col, y + ROW * row++);
		toggle(col, y, row++, "Denoise", () -> config.effects.denoise, v -> config.effects.denoise = v);
		slider(col, y, row++, "Ray Length", config.effects.rayLength, 1.0, 16.0,
			v -> config.effects.rayLength = v, "m");
		toggle(col, y, row++, "Volumetric Light", () -> config.effects.volumetricLight,
			v -> config.effects.volumetricLight = v);
		slider(col, y, row++, "Volumetric Strength", config.effects.volumetricStrength, 0.0, 1.0,
			v -> config.effects.volumetricStrength = v, "%");
		toggle(col, y, row++, "Bloom / Emissive Glow", () -> config.effects.bloom,
			v -> config.effects.bloom = v);
		slider(col, y, row++, "Bloom Strength", config.effects.bloomStrength, 0.0, 1.0,
			v -> config.effects.bloomStrength = v, "%");
		toggle(col, y, row++, "Motion Blur", () -> config.effects.motionBlur,
			v -> config.effects.motionBlur = v);
		slider(col, y, row++, "Motion Blur Amount", config.effects.motionBlurStrength, 0.0, 1.0,
			v -> config.effects.motionBlurStrength = v, "%");

		// ---------------- Footer ----------------
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(this.width / 2 - 104, yEnd, 100, H).build());
		addRenderableWidget(Button.builder(Component.literal("Defaults"), b -> {
			applyDefaults();
			MetalPipeline.onConfigMutated();
			rebuildWidgets();
		}).bounds(this.width / 2 + 4, yEnd, 100, H).build());
	}

	private void applyDefaults() {
		config.enabled = true;
		config.qualityPreset = MMetalConfig.QualityPreset.QUALITY;
		config.upscaleMode = MMetalConfig.UpscaleMode.SPATIAL;
		config.frameInterpolation = false;
		config.hdrOutput = false;
		config.effects = new MMetalConfig.Effects();
		MetalPipeline.onConfigMutated();
	}

	// ---------------------------------------------------------------- helpers

	private void toggle(int x, int baseY, int row, String label, BooleanSupplier get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(toggleLabel(label, get.getAsBoolean()), b -> {
			boolean value = !get.getAsBoolean();
			set.accept(value);
			b.setMessage(toggleLabel(label, value));
			MetalPipeline.onConfigMutated();
		}).bounds(x, baseY + ROW * row, W, H).build());
	}

	private void slider(int x, int baseY, int row, String label, double value, double min, double max,
	                    DoubleConsumer set, String unit) {
		addRenderableWidget(new AbstractSliderButton(x, baseY + ROW * row, W, H,
			sliderLabel(label, value, unit), norm(value, min, max)) {
			@Override
			protected void updateMessage() {
				setMessage(sliderLabel(label, currentValue(), unit));
			}

			@Override
			protected void applyValue() {
				set.accept(currentValue());
				MetalPipeline.onConfigMutated();
			}

			private double currentValue() {
				double v = min + this.value * (max - min);
				return Math.round(v * 100.0) / 100.0;
			}
		});
	}

	private void qualityButton(int x, int rowY) {
		addRenderableWidget(Button.builder(qualityLabel(), b -> {
			MMetalConfig.QualityPreset[] presets = MMetalConfig.QualityPreset.values();
			config.qualityPreset = presets[(config.qualityPreset.ordinal() + 1) % presets.length];
			b.setMessage(qualityLabel());
			MetalPipeline.onConfigMutated();
		}).bounds(x, rowY, W, H).build());
	}

	private void upscaleButton(int x, int rowY) {
		addRenderableWidget(Button.builder(Component.literal("Upscale: " + config.upscaleMode), b -> {
			MMetalConfig.UpscaleMode[] modes = MMetalConfig.UpscaleMode.values();
			config.upscaleMode = modes[(config.upscaleMode.ordinal() + 1) % modes.length];
			b.setMessage(Component.literal("Upscale: " + config.upscaleMode));
			MetalPipeline.onConfigMutated();
		}).bounds(x, rowY, W, H).build());
	}

	private void rayCountButton(int x, int rowY) {
		addRenderableWidget(Button.builder(rayCountLabel(), b -> {
			int steps = config.effects.marchSteps;
			int next = steps < 12 ? 16 : steps < 20 ? 24 : 8;
			config.effects.marchSteps = next;
			b.setMessage(rayCountLabel());
			MetalPipeline.onConfigMutated();
		}).bounds(x, rowY, W, H).build());
	}

	private Component qualityLabel() {
		MMetalConfig.QualityPreset preset = config.qualityPreset;
		if (preset == MMetalConfig.QualityPreset.NONE) {
			return Component.literal("Quality: None (Native)");
		}
		return Component.literal(String.format(Locale.ROOT, "Quality: %s (%.0f%%)",
			preset.label, preset.scale * 100));
	}

	private Component rayCountLabel() {
		int steps = config.effects.marchSteps;
		String tier = steps < 12 ? "Low" : steps < 20 ? "Medium" : "High";
		return Component.literal("Ray Count: " + tier + " (" + steps + ")");
	}

	private static Component toggleLabel(String name, boolean on) {
		return Component.literal(name + ": " + (on ? "ON" : "OFF"));
	}

	private static Component sliderLabel(String name, double value, String unit) {
		if ("x".equals(unit)) {
			return Component.literal(String.format(Locale.ROOT, "%s: %.2fx", name, value));
		}
		if ("m".equals(unit)) {
			return Component.literal(String.format(Locale.ROOT, "%s: %.1f", name, value));
		}
		return Component.literal(String.format(Locale.ROOT, "%s: %.0f%%", name, value * 100));
	}

	private static double norm(double v, double min, double max) {
		return Math.max(0.0, Math.min(1.0, (v - min) / (max - min)));
	}

	@Override
	public void onClose() {
		config.save();
		MetalPipeline.onConfigMutated();
		super.onClose();
	}

	/** One-line capability summary for the log/status command. */
	public static String capabilitySummary() {
		if (!SystemInfo.isMac()) {
			return "macOS only";
		}
		if (!MetalNative.available()) {
			return "no Metal device";
		}
		return String.format("%s | spatial=%s temporal=%s frameInterpolation=%s",
			MetalNative.deviceName(),
			MetalFx.supportsSpatial(MetalNative.device()),
			MetalFx.supportsTemporal(MetalNative.device()),
			SystemInfo.supportsFrameInterpolation()
				&& MetalFx.supportsFrameInterpolation(MetalNative.device()));
	}

	public static void open() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			mc.setScreenAndShow(new MMetalConfigScreen());
		}
	}
}