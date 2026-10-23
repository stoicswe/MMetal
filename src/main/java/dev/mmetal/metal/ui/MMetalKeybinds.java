package dev.mmetal.metal.ui;

import dev.mmetal.metal.render.MetalPipeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Registers the "Open MMetal Settings" keybind (default O, MISC category) and opens
 * the config screen when it is pressed in-game.
 */
public final class MMetalKeybinds {
	private static KeyMapping openSettings;

	private MMetalKeybinds() {
	}

	public static void register() {
		// GLFW_KEY_O == 79, referenced as a literal: Minecraft 26.3 ships SDL, not GLFW.
		openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.mmetal.openSettings", 79, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Key mappings only receive input while no screen is open, so no extra
			// "is a screen showing" check is needed.
			while (openSettings.consumeClick()) {
				MMetalConfigScreen.open();
			}
		});
	}
}
