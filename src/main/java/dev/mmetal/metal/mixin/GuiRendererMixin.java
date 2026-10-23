package dev.mmetal.metal.mixin;

import dev.mmetal.metal.render.MetalPipeline;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Scales GUI scissor rectangles from native pixels into the reduced render target.
 *
 * <p>{@code GuiRenderer.enableScissor} computes its scissor in native window pixels
 * (gui-scaled coordinates times the GUI scale) and clamps it against the native
 * {@code WindowRenderState}. The default framebuffer we actually draw into is the
 * scaled render target, so the rectangle must be scaled to the same space - otherwise
 * RenderPearl rejects it as out of bounds.</p>
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

	@ModifyArgs(method = "enableScissor",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/RenderPass;enableScissor(IIII)V"))
	private void mmetal$scaleScissor(Args args) {
		float scale = MetalPipeline.currentScaleFactor();
		if (scale >= 0.999f) {
			return;
		}
		int x = Math.max(0, Math.round(args.<Integer>get(0) * scale));
		int y = Math.max(0, Math.round(args.<Integer>get(1) * scale));
		int w = Math.max(0, Math.round(args.<Integer>get(2) * scale));
		int h = Math.max(0, Math.round(args.<Integer>get(3) * scale));
		args.set(0, x);
		args.set(1, y);
		args.set(2, w);
		args.set(3, h);
	}
}
