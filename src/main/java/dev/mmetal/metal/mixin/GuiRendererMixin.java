package dev.mmetal.metal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mmetal.metal.render.MetalPipeline;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the GUI render pass to a full-resolution target when the native-UI pass
 * is active, so the HUD and text render at the window's real resolution while the
 * world renders at the reduced upscale tier.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

	@Redirect(method = "draw",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget mmetal$nativeUiTarget(net.minecraft.client.renderer.GameRenderer gameRenderer) {
		MetalPipeline.uiRedirectCalls++;
		RenderTarget ui = MetalPipeline.nativeUiTarget();
		if (ui != null) {
			MetalPipeline.uiRedirectUiHits++;
			MetalPipeline.clearNativeUiIfPending();
			return ui;
		}
		return gameRenderer.mainRenderTarget();
	}
}