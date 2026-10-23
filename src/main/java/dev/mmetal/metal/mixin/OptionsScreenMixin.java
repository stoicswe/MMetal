package dev.mmetal.metal.mixin;

import dev.mmetal.metal.render.MetalPipeline;
import dev.mmetal.metal.ui.MMetalConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "MMetal Settings…" button to the vanilla Options screen (bottom-left),
 * opening the MMetal configuration screen.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {

	@Inject(method = "init", at = @At("TAIL"))
	private void mmetal$addSettingsButton(CallbackInfo ci) {
		if (MetalPipeline.config() == null) {
			return;
		}
		Screen self = (Screen) (Object) this;
		((ScreenInvoker) this).mmetal$addRenderableWidget(Button.builder(Component.literal("MMetal Settings"), button ->
				MMetalConfigScreen.open())
			.bounds(8, self.height - 28, 110, 20)
			.build());
	}
}