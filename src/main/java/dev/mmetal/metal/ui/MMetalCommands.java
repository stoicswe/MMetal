package dev.mmetal.metal.ui;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mmetal.metal.config.MMetalConfig;
import dev.mmetal.metal.render.MetalPipeline;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * {@code /mmetal} client command: opens the config UI and tweaks settings live.
 * Every subcommand prints the resulting state back to the player.
 */
public final class MMetalCommands {
	private MMetalCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommands.literal("mmetal")
				.then(ClientCommands.literal("ui").executes(ctx -> {
					MMetalConfigScreen.open();
					return 1;
				}))
				.then(ClientCommands.literal("status").executes(ctx -> {
					feedback(MetalPipeline.statusLine());
					return 1;
				}))
				.then(ClientCommands.literal("reload").executes(ctx -> {
					MMetalConfig reloaded = MMetalConfig.load();
					apply(reloaded);
					feedback("Reloaded: " + MetalPipeline.statusLine());
					return 1;
				}))
				.then(ClientCommands.literal("quality").then(
					ClientCommands.argument("preset", StringArgumentType.word())
						.executes(ctx -> {
							try {
								String input = StringArgumentType.getString(ctx, "preset")
									.toUpperCase(Locale.ROOT).replace('-', '_');
								MMetalConfig config = MetalPipeline.config();
								config.qualityPreset = MMetalConfig.QualityPreset.valueOf(input);
								MetalPipeline.onConfigMutated();
								feedback("Quality = " + config.qualityPreset.label
									+ " (" + Math.round(config.qualityPreset.scale * 100) + "%)");
							} catch (IllegalArgumentException e) {
								feedback("Unknown preset - use ULTRA_PERFORMANCE, PERFORMANCE, BALANCED, QUALITY or NONE");
							}
							return 1;
						})))
				.then(ClientCommands.literal("upscale").then(
					ClientCommands.argument("mode", StringArgumentType.word())
						.executes(ctx -> {
							try {
								MMetalConfig config = MetalPipeline.config();
								config.upscaleMode = MMetalConfig.UpscaleMode.valueOf(
									StringArgumentType.getString(ctx, "mode").toUpperCase(Locale.ROOT));
								MetalPipeline.onConfigMutated();
								feedback("upscaleMode = " + config.upscaleMode);
							} catch (IllegalArgumentException e) {
								feedback("Unknown mode - use OFF, SPATIAL or TEMPORAL");
							}
							return 1;
						})))
				.then(ClientCommands.literal("iris").then(
					ClientCommands.argument("mode", StringArgumentType.word())
						.executes(ctx -> {
							try {
								MMetalConfig config = MetalPipeline.config();
								config.irisMode = MMetalConfig.IrisMode.valueOf(
									StringArgumentType.getString(ctx, "mode").toUpperCase(Locale.ROOT));
								MetalPipeline.onConfigMutated();
								feedback("irisMode = " + config.irisMode
									+ " (STAND_DOWN = vanilla passthrough, METALFX = upscale + framegen,"
									+ " FULL = full pipeline under the pack)");
							} catch (IllegalArgumentException e) {
								feedback("Unknown mode - use STAND_DOWN, METALFX or FULL");
							}
							return 1;
						})))
				.then(ClientCommands.literal("palette").then(
					ClientCommands.argument("name", StringArgumentType.word())
						.executes(ctx -> {
							try {
								MMetalConfig config = MetalPipeline.config();
								config.palette = MMetalConfig.Palette.valueOf(
									StringArgumentType.getString(ctx, "name").toUpperCase(Locale.ROOT));
								MetalPipeline.onConfigMutated();
								feedback("palette = " + config.palette
									+ " (FILMIC = SDR filmic, EXTENDED = HDR look in SDR,"
									+ " VIBRANT = vanilla-like saturation, also in HDR, NEUTRAL = raw)");
							} catch (IllegalArgumentException e) {
								feedback("Unknown palette - use FILMIC, EXTENDED, NEUTRAL or VIBRANT");
							}
							return 1;
						})))
				.then(ClientCommands.literal("shaderpack")
					.executes(ctx -> {
						listShaderPacks();
						return 1;
					})
					.then(ClientCommands.argument("name", StringArgumentType.greedyString())
						.executes(ctx -> {
							inspectShaderPack(StringArgumentType.getString(ctx, "name"));
							return 1;
						})))
				.then(ClientCommands.literal("framegen").then(
					ClientCommands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
						MMetalConfig config = MetalPipeline.config();
						config.frameInterpolation = BoolArgumentType.getBool(ctx, "on");
						MetalPipeline.onConfigMutated();
						feedback("frameInterpolation = " + config.frameInterpolation
							+ " (set Max FPS to half your refresh rate)");
						return 1;
					})))
				.then(ClientCommands.literal("effects").then(
					ClientCommands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
						MMetalConfig config = MetalPipeline.config();
						config.effects.enabled = BoolArgumentType.getBool(ctx, "on");
						MetalPipeline.onConfigMutated();
						feedback("effects.enabled = " + config.effects.enabled);
						return 1;
					})))
				.then(ClientCommands.literal("shadows").then(
					ClientCommands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
						MMetalConfig config = MetalPipeline.config();
						config.effects.sunShadows = BoolArgumentType.getBool(ctx, "on");
						MetalPipeline.onConfigMutated();
						feedback("effects.sunShadows = " + config.effects.sunShadows);
						return 1;
					})))
				.then(ClientCommands.literal("ao").then(
					ClientCommands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
						MMetalConfig config = MetalPipeline.config();
						config.effects.ambientOcclusion = BoolArgumentType.getBool(ctx, "on");
						MetalPipeline.onConfigMutated();
						feedback("effects.ambientOcclusion = " + config.effects.ambientOcclusion);
						return 1;
					})))
				.then(ClientCommands.literal("sunstrength").then(
					ClientCommands.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f)).executes(ctx -> {
						MMetalConfig config = MetalPipeline.config();
						config.effects.sunStrength = FloatArgumentType.getFloat(ctx, "value");
						MetalPipeline.onConfigMutated();
						feedback("sunStrength = " + config.effects.sunStrength);
						return 1;
					})))
				.then(ClientCommands.literal("aostrength").then(
					ClientCommands.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f)).executes(ctx -> {
						MMetalConfig config = MetalPipeline.config();
						config.effects.aoStrength = FloatArgumentType.getFloat(ctx, "value");
						MetalPipeline.onConfigMutated();
						feedback("aoStrength = " + config.effects.aoStrength);
						return 1;
					})))));
	}

	private static void apply(MMetalConfig reloaded) {
		MMetalConfig config = MetalPipeline.config();
		config.enabled = reloaded.enabled;
		config.qualityPreset = reloaded.qualityPreset;
		config.renderScale = reloaded.renderScale;
		config.upscaleMode = reloaded.upscaleMode;
		config.irisMode = reloaded.irisMode;
		config.palette = reloaded.palette;
		config.frameInterpolation = reloaded.frameInterpolation;
		config.effects = reloaded.effects;
		MetalPipeline.onConfigMutated();
	}

	// ------------------------------------------------------------------
	// Shader pack inspection (translation ladder rung 1)
	// ------------------------------------------------------------------

	private static java.nio.file.Path shaderpackDir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	private static void listShaderPacks() {
		java.nio.file.Path dir = shaderpackDir();
		if (!java.nio.file.Files.isDirectory(dir)) {
			feedback("No shaderpacks folder at " + dir);
			return;
		}
		boolean any = false;
		try (var stream = java.nio.file.Files.list(dir)) {
			for (java.nio.file.Path path : stream.sorted().toList()) {
				String file = path.getFileName().toString();
				if (!file.toLowerCase(Locale.ROOT).endsWith(".zip")
					&& !java.nio.file.Files.isDirectory(path)) {
					continue;
				}
				any = true;
				try {
					dev.mmetal.metal.shaderpack.IrisPack pack =
						dev.mmetal.metal.shaderpack.IrisPack.load(path);
					feedback(pack.name() + " - " + pack.programs().size() + " programs, GLSL max "
						+ (pack.maxGlslVersion() == 0 ? "?" : pack.maxGlslVersion()) + ", "
						+ (pack.gl41Compatible() ? "runs on GL 4.1" : "needs Metal translation"));
				} catch (Exception e) {
					feedback(file + " - not a shader pack (" + e.getMessage() + ")");
				}
			}
		} catch (java.io.IOException e) {
			feedback("Failed to list shaderpacks: " + e.getMessage());
			return;
		}
		if (!any) {
			feedback("No shader packs installed in " + dir);
		}
	}

	private static void inspectShaderPack(String name) {
		java.nio.file.Path dir = shaderpackDir();
		java.nio.file.Path target = null;
		try (var stream = java.nio.file.Files.list(dir)) {
			for (java.nio.file.Path path : stream.toList()) {
				String file = path.getFileName().toString();
				String bare = file.toLowerCase(Locale.ROOT).endsWith(".zip")
					? file.substring(0, file.length() - 4) : file;
				if (file.equals(name) || file.equalsIgnoreCase(name)
					|| bare.equalsIgnoreCase(name)) {
					target = path;
					break;
				}
			}
		} catch (java.io.IOException e) {
			feedback("Failed to list shaderpacks: " + e.getMessage());
			return;
		}
		if (target == null) {
			feedback("No shader pack named '" + name + "' in " + dir);
			return;
		}
		try {
			dev.mmetal.metal.shaderpack.IrisPack pack =
				dev.mmetal.metal.shaderpack.IrisPack.load(target);
			for (String line : pack.summary()) {
				feedback(line);
			}
			dev.mmetal.metal.MetalFxMod.LOGGER.info("[MMetal] shaderpack {}: {}",
				pack.name(), String.join(" | ", pack.summary()));
		} catch (Exception e) {
			feedback("Failed to parse " + name + ": " + e.getMessage());
		}
	}

	private static void feedback(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[MMetal] " + message));
		}
	}
}