package dev.mmetal.metal.ui;

import dev.mmetal.api.MMetalStyle;
import dev.mmetal.api.MMetalStyles;
import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.nativebridge.MetalFx;
import dev.mmetal.metal.nativebridge.MetalNative;
import dev.mmetal.metal.nativebridge.SystemInfo;
import dev.mmetal.metal.render.MetalPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * In-game configuration screen for MMetal.
 *
 * <p>Tabs group the options (Render / Ray Tracing / Effects / Held Light); within a
 * tab, rows are grouped under section headers and the content scrolls when it does
 * not fit. Open with O, the Options-screen button, or {@code /mmetal ui}.</p>
 */
public final class MMetalConfigScreen extends Screen {
	private static final int MAX_PANEL_W = 460;
	private static final int ROW = 22;
	private static final int BH = 18;
	private static final int TAB_GAP = 4;

	private static final String[] TABS = {"Render", "Ray Tracing", "Effects", "Held Light", "Style"};

	private final MMetalConfig config = MetalPipeline.config();

	private int tab;
	private int scroll;
	private int maxScroll;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int contentTop;
	private int viewH;

	private final List<Section> sections = new ArrayList<>();
	private int cursor;

	private record Section(String title, int y) {
	}

	public MMetalConfigScreen() {
		super(Component.literal("MMetal — Metal / MetalFX"));
	}

	@Override
	protected void init() {
		panelW = Math.min(MAX_PANEL_W, this.width - 48);
		panelX = (this.width - panelW) / 2;

		// ---- Tabs ----
		int tabsY = 30;
		int tabW = (panelW - TAB_GAP * (TABS.length - 1)) / TABS.length;
		for (int i = 0; i < TABS.length; i++) {
			final int index = i;
			addRenderableWidget(Button.builder(Component.literal(tabLabel(i)),
					b -> switchTab(index))
				.bounds(panelX + i * (tabW + TAB_GAP), tabsY, tabW, BH).build());
		}

		// ---- Content viewport ----
		int footerY = this.height - 30;
		panelY = tabsY + BH + 10;
		panelH = Math.max(60, footerY - 10 - panelY);
		contentTop = panelY + 8;
		viewH = panelH - 16;

		sections.clear();
		cursor = 0;
		buildTab();
		int contentH = cursor;
		maxScroll = Math.max(0, contentH - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));

		// ---- Footer ----
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(this.width / 2 - 104, footerY, 100, BH).build());
		addRenderableWidget(Button.builder(Component.literal("Defaults"), b -> {
			applyDefaults();
			rebuildWidgets();
		}).bounds(this.width / 2 + 4, footerY, 100, BH).build());
	}

	// ---------------------------------------------------------------- tabs

	private void switchTab(int index) {
		if (tab != index) {
			tab = index;
			scroll = 0;
			rebuildWidgets();
		}
	}

	private String tabLabel(int index) {
		return (tab == index ? "» " : "") + TABS[index];
	}

	private void buildTab() {
		switch (tab) {
			case 0 -> buildRender();
			case 1 -> buildRayTracing();
			case 2 -> buildEffects();
			case 3 -> buildHeldLight();
			default -> buildStyle();
		}
	}

	private void buildRender() {
		section("Pipeline");
		toggle("MMetal", () -> config.enabled, v -> config.enabled = v);
		qualityButton();
		upscaleButton();
		toggle("Frame Interpolation", () -> config.frameInterpolation,
			v -> config.frameInterpolation = v);

		section("Display");
		toggle("HDR Output (EDR)", () -> config.hdrOutput, v -> config.hdrOutput = v);
		toggle("Filmic Tonemap", () -> config.effects.tonemap, v -> config.effects.tonemap = v);
		slider("Exposure", config.effects.exposure, 0.25, 3.0,
			v -> config.effects.exposure = v, "x");
	}

	private void buildRayTracing() {
		section("Tracing");
		toggle("Ray Traced Lighting", () -> config.effects.rayTracing,
			v -> config.effects.rayTracing = v);
		rayCountButton();
		slider("Ray Length", config.effects.rayLength, 1.0, 16.0,
			v -> config.effects.rayLength = v, "m");
		toggle("Denoise", () -> config.effects.denoise, v -> config.effects.denoise = v);

		section("Sun Shadows");
		toggle("RT Shadows", () -> config.effects.sunShadows, v -> config.effects.sunShadows = v);
		slider("Shadow Strength", config.effects.sunStrength, 0.0, 1.0,
			v -> config.effects.sunStrength = v, "%");
		toggle("RT Shadows (Hardware)", () -> config.effects.hardwareRtShadows,
			v -> config.effects.hardwareRtShadows = v);
		toggle("Sun From World Time", () -> config.effects.sunFromWorldTime,
			v -> config.effects.sunFromWorldTime = v);

		section("Ambient & Bounce");
		toggle("RT Ambient Occlusion", () -> config.effects.ambientOcclusion,
			v -> config.effects.ambientOcclusion = v);
		slider("AO Strength", config.effects.aoStrength, 0.0, 1.0,
			v -> config.effects.aoStrength = v, "%");
		toggle("RT Global Illumination", () -> config.effects.globalIllumination,
			v -> config.effects.globalIllumination = v);
		slider("GI Strength", config.effects.giStrength, 0.0, 1.0,
			v -> config.effects.giStrength = v, "%");

		section("Reflections");
		toggle("RT Reflections", () -> config.effects.reflections,
			v -> config.effects.reflections = v);
		slider("Reflection Strength", config.effects.reflectionStrength, 0.0, 1.0,
			v -> config.effects.reflectionStrength = v, "%");
	}

	private void buildEffects() {
		section("Atmosphere");
		toggle("Volumetric Light", () -> config.effects.volumetricLight,
			v -> config.effects.volumetricLight = v);
		slider("Volumetric Strength", config.effects.volumetricStrength, 0.0, 1.0,
			v -> config.effects.volumetricStrength = v, "%");

		section("Post Processing");
		toggle("Bloom / Emissive Glow", () -> config.effects.bloom,
			v -> config.effects.bloom = v);
		slider("Bloom Strength", config.effects.bloomStrength, 0.0, 1.0,
			v -> config.effects.bloomStrength = v, "%");
		toggle("Motion Blur", () -> config.effects.motionBlur,
			v -> config.effects.motionBlur = v);
		slider("Motion Blur Amount", config.effects.motionBlurStrength, 0.0, 1.0,
			v -> config.effects.motionBlurStrength = v, "%");
	}

	private void buildHeldLight() {
		section("Held Light");
		toggle("Held Light Glow", () -> config.effects.heldLightGlow,
			v -> config.effects.heldLightGlow = v);
		slider("Held Light Strength", config.effects.heldLightStrength, 0.0, 1.0,
			v -> config.effects.heldLightStrength = v, "%");
		toggle("Firelight Flicker", () -> config.effects.heldLightFlicker,
			v -> config.effects.heldLightFlicker = v);

		section("Torch Flame");
		toggle("Flame Particles", () -> config.effects.heldLightParticles,
			v -> config.effects.heldLightParticles = v);
		slider("Flame Height", config.effects.flameTipHeight, 0.0, 0.8,
			v -> config.effects.flameTipHeight = v, "m");
	}

	private void buildStyle() {
		section("Look");
		styleButton();
		slider("Style Strength", config.styleStrength, 0.0, 1.0,
			v -> config.styleStrength = v, "%");

		section("About");
		int y = nextRow();
		if (y >= 0) {
			Button about = Button.builder(Component.literal(currentStyleDescription()), b -> {
				})
				.bounds(rowX(), y, rowW(), BH).build();
			about.active = false;
			addRenderableWidget(about);
		}
		int hintY = nextRow();
		if (hintY >= 0) {
			Button hint = Button.builder(
					Component.literal("Drop style jars in mods/ - see docs/STYLES.md"), b -> {
					})
				.bounds(rowX(), hintY, rowW(), BH).build();
			hint.active = false;
			addRenderableWidget(hint);
		}
	}

	private String currentStyleDescription() {
		MMetalStyle style = MMetalStyles.get(config.styleId);
		String text = style.description() == null ? "" : style.description();
		return text.length() > 58 ? text.substring(0, 55) + "..." : text;
	}

	private void styleButton() {
		int y = nextRow();
		if (y < 0) {
			return;
		}
		Button button = Button.builder(styleLabel(), b -> {
			java.util.List<MMetalStyle> styles = MMetalStyles.all();
			int index = 0;
			for (int i = 0; i < styles.size(); i++) {
				if (styles.get(i).id().equalsIgnoreCase(config.styleId)) {
					index = i;
					break;
				}
			}
			MMetalStyle next = styles.get((index + 1) % styles.size());
			config.styleId = next.id();
			if (config.styleStrength <= 0.01) {
				config.styleStrength = next.defaultStrength();
			}
			b.setMessage(styleLabel());
			MetalPipeline.onConfigMutated();
			rebuildWidgets();
		}).bounds(rowX(), y, rowW(), BH).build();
		// Nothing to choose from: keep the button visible but inert.
		button.active = MMetalStyles.all().size() > 1;
		addRenderableWidget(button);
	}

	private Component styleLabel() {
		MMetalStyle style = MMetalStyles.get(config.styleId);
		String tag = style.builtIn() ? "" : " (mod)";
		return Component.literal("Style: " + style.displayName() + tag);
	}

	// ---------------------------------------------------------------- rows

	private void section(String title) {
		if (cursor > 0) {
			cursor += 8;
		}
		sections.add(new Section(title, contentTop + cursor - scroll));
		cursor += 18;
	}

	/** Returns the screen-space y for the next row, or -1 when it is out of view. */
	private int nextRow() {
		int y = contentTop + cursor - scroll;
		cursor += ROW;
		return (y >= contentTop && y + BH <= contentTop + viewH) ? y : -1;
	}

	private int rowX() {
		return panelX + 6;
	}

	private int rowW() {
		return panelW - 12;
	}

	private void toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
		int y = nextRow();
		if (y < 0) {
			return;
		}
		addRenderableWidget(Button.builder(toggleLabel(label, get.getAsBoolean()), b -> {
			boolean value = !get.getAsBoolean();
			set.accept(value);
			b.setMessage(toggleLabel(label, value));
			MetalPipeline.onConfigMutated();
		}).bounds(rowX(), y, rowW(), BH).build());
	}

	private void slider(String label, double value, double min, double max,
	                    DoubleConsumer set, String unit) {
		int y = nextRow();
		if (y < 0) {
			return;
		}
		addRenderableWidget(new AbstractSliderButton(rowX(), y, rowW(), BH,
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

	private void qualityButton() {
		int y = nextRow();
		if (y < 0) {
			return;
		}
		addRenderableWidget(Button.builder(qualityLabel(), b -> {
			MMetalConfig.QualityPreset[] presets = MMetalConfig.QualityPreset.values();
			config.qualityPreset = presets[(config.qualityPreset.ordinal() + 1) % presets.length];
			b.setMessage(qualityLabel());
			MetalPipeline.onConfigMutated();
		}).bounds(rowX(), y, rowW(), BH).build());
	}

	private void upscaleButton() {
		int y = nextRow();
		if (y < 0) {
			return;
		}
		addRenderableWidget(Button.builder(Component.literal("Upscale: " + config.upscaleMode), b -> {
			MMetalConfig.UpscaleMode[] modes = MMetalConfig.UpscaleMode.values();
			config.upscaleMode = modes[(config.upscaleMode.ordinal() + 1) % modes.length];
			b.setMessage(Component.literal("Upscale: " + config.upscaleMode));
			MetalPipeline.onConfigMutated();
		}).bounds(rowX(), y, rowW(), BH).build());
	}

	private void rayCountButton() {
		int y = nextRow();
		if (y < 0) {
			return;
		}
		addRenderableWidget(Button.builder(rayCountLabel(), b -> {
			int steps = config.effects.marchSteps;
			int next = steps < 12 ? 16 : steps < 20 ? 24 : 8;
			config.effects.marchSteps = next;
			b.setMessage(rayCountLabel());
			MetalPipeline.onConfigMutated();
		}).bounds(rowX(), y, rowW(), BH).build());
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Panel background.
		g.fill(panelX - 8, panelY - 4, panelX + panelW + 8, panelY + panelH + 2, 0xC0101010);
		// Section headers (only those inside the viewport).
		for (Section section : sections) {
			if (section.y() >= contentTop - 2 && section.y() + 12 <= contentTop + viewH + 2) {
				g.text(this.font, section.title().toUpperCase(Locale.ROOT),
					panelX + 6, section.y(), 0xFFE8C060);
				g.fill(panelX + 6, section.y() + 11, panelX + panelW - 6, section.y() + 12, 0x50E8C060);
			}
		}
		// Scrollbar.
		if (maxScroll > 0) {
			int trackX = panelX + panelW - 3;
			g.fill(trackX, contentTop, trackX + 2, contentTop + viewH, 0x30FFFFFF);
			int thumbH = Math.max(20, viewH * viewH / (viewH + maxScroll));
			int thumbY = contentTop + (viewH - thumbH) * scroll / maxScroll;
			g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xA0FFFFFF);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (maxScroll > 0 && scrollY != 0.0) {
			int before = scroll;
			scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * 26.0));
			if (scroll != before) {
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// ---------------------------------------------------------------- misc

	private void applyDefaults() {
		config.enabled = true;
		config.qualityPreset = MMetalConfig.QualityPreset.QUALITY;
		config.upscaleMode = MMetalConfig.UpscaleMode.SPATIAL;
		config.frameInterpolation = false;
		config.hdrOutput = false;
		config.styleId = "none";
		config.styleStrength = 1.0;
		config.effects = new MMetalConfig.Effects();
		MetalPipeline.onConfigMutated();
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
