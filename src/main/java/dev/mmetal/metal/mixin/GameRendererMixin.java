package dev.mmetal.metal.mixin;

import dev.mmetal.metal.render.MetalPipeline;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reduces the world render target resolution for MetalFX upscaling.
 *
 * <p>The scale is applied to the extracted {@link WindowRenderState}. Everything that
 * follows from it stays coherent:</p>
 * <ul>
 *   <li>the main render target is resized to the scaled size in {@code render()},</li>
 *   <li>the GUI scissor clamp (which reads these fields) matches the scaled target -
 *       clamping against the native size would throw an out-of-bounds scissor,</li>
 *   <li>{@code Window.getGuiScaledWidth/Height} are scaled by the same factor
 *       ({@code WindowMixin}), so the GUI layout, the scissor math and the mouse
 *       mapping all live in the same scaled coordinate space.</li>
 * </ul>
 * The whole frame (world + GUI) is then rendered at the reduced resolution and
 * upscaled as a single image.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Inject(method = "extractWindow", at = @At("TAIL"))
	private void mmetal$scaleWindowRenderState(CallbackInfo ci) {
		float scale = MetalPipeline.currentScaleFactor();
		if (scale >= 0.999f) {
			return;
		}
		WindowRenderState state = this.gameRenderState.windowRenderState;
		state.width = Math.max(1, Math.round(state.width * scale));
		state.height = Math.max(1, Math.round(state.height * scale));
	}
}
