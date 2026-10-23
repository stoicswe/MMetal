package dev.mmetal.metal.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Screen's protected widget-registration methods to other mixins.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

	@Invoker("addRenderableWidget")
	<T extends GuiEventListener & Renderable & NarratableEntry> T mmetal$addRenderableWidget(T widget);
}