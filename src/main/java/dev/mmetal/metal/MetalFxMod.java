package dev.mmetal.metal;

import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.hook.Compat;
import dev.mmetal.metal.nativebridge.SystemInfo;
import dev.mmetal.metal.render.MetalPipeline;
import dev.mmetal.metal.ui.MMetalCommands;
import dev.mmetal.metal.ui.MMetalKeybinds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MMetal - Metal 4 / MetalFX integration for Minecraft on Apple Silicon.
 *
 * <p>Startup is intentionally conservative: the mod boots, reports its capabilities,
 * loads {@code config/mmetal.json} and hands control to the render pipeline, which
 * activates on the first present. Nothing here can crash the game - every stage
 * fails safe.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalFxMod implements ClientModInitializer {
	public static final String MOD_ID = "mmetal";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		if (!SystemInfo.isMac()) {
			LOGGER.warn("MMetal is a macOS-only mod (Metal/MetalFX); staying inactive on this platform.");
			return;
		}
		if (!SystemInfo.isArm64()) {
			LOGGER.warn("MMetal targets Apple Silicon; MetalFX may be unavailable on Intel Macs.");
		}
		if (!SystemInfo.supportsMetalFxUpscaling()) {
			LOGGER.warn("macOS 13+ is required for MetalFX upscaling (found {}.{}) - staying inactive.",
				SystemInfo.macOSMajor(), SystemInfo.macOSMinor());
			return;
		}
		LOGGER.info("MMetal booting: macOS {}.{}, Java {}, Sodium={}, Iris={}",
			SystemInfo.macOSMajor(), SystemInfo.macOSMinor(),
			System.getProperty("java.version"),
			Compat.isSodiumPresent() ? "present" : "absent",
			Compat.isIrisPresent() ? "present" : "absent");
		MetalPipeline.initialize(MMetalConfig.load());
		MMetalCommands.register();
		MMetalKeybinds.register();
	}
}