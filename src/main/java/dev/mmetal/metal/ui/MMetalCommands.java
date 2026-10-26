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
									+ (config.qualityPreset == MMetalConfig.QualityPreset.DYNAMIC
										? " (follows frame time)"
										: " (" + Math.round(config.qualityPreset.scale * 100) + "%)"));
							} catch (IllegalArgumentException e) {
								feedback("Unknown preset - use ULTRA_PERFORMANCE, PERFORMANCE, BALANCED, QUALITY, DYNAMIC or NONE");
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
		config.effects = reloaded.effects;
		MetalPipeline.onConfigMutated();
	}

	private static void feedback(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[MMetal] " + message));
		}
	}
}