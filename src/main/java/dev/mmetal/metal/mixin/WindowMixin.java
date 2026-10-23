package dev.mmetal.metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.Window;
import dev.mmetal.metal.render.MetalPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scales the GUI *coordinate space* with the render scale.
 *
 * <p>Why only these two getters: the GUI is rendered into the reduced-resolution main
 * target, and {@code GuiRenderer} converts GUI coordinates to pixels as
 * {@code coord * guiScale} (clamped to the render target size). Keeping the GUI space
 * proportional to the target means the scissor rectangles, the GUI projection and the
 * widget layout all agree with each other and with the target.</p>
 *
 * <p>{@code getWidth()/getHeight()} are deliberately left at native resolution: the
 * mouse handler converts pixel coordinates to GUI coordinates as
 * {@code pixel * guiScaledWidth / getWidth()}, so both sides must share the same
 * (native) framebuffer size for clicks to land where the buttons are drawn.</p>
 */
@Mixin(Window.class)
public abstract class WindowMixin {

	@ModifyReturnValue(method = "getGuiScaledWidth()I", at = @At("RETURN"))
	private int mmetal$scaleGuiWidth(int original) {
		float scale = MetalPipeline.currentScaleFactor();
		return scale >= 0.999f ? original : Math.max(1, (int) (original * scale));
	}

	@ModifyReturnValue(method = "getGuiScaledHeight()I", at = @At("RETURN"))
	private int mmetal$scaleGuiHeight(int original) {
		float scale = MetalPipeline.currentScaleFactor();
		return scale >= 0.999f ? original : Math.max(1, (int) (original * scale));
	}
}