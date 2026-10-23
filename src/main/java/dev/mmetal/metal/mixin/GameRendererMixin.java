package dev.mmetal.metal.mixin;

import dev.mmetal.metal.render.MetalPipeline;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reduces the world render target resolution for MetalFX upscaling.
 *
 * <p>The scale is applied ONLY to the {@code WindowRenderState.width/height} reads
 * inside {@code GameRenderer.render()} (the comparison and the
 * {@code resize(width, height)} call). {@code WindowRenderState} itself stays at the
 * native window size so that:
 * <ul>
 *   <li>the GUI scissor math ({@code GuiRenderer.enableScissor} clamps against these
 *       fields) stays consistent with the full-resolution GUI render pass,</li>
 *   <li>the GUI layout and mouse mapping are unaffected.</li>
 * </ul>
 * The world then renders into a reduced-resolution target (via the redirected resize)
 * while the GUI renders at native resolution into a separate full-resolution target
 * ({@code GuiRendererMixin} redirects the GUI pass to it).</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Redirect(method = "render",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/WindowRenderState;width:I",
			opcode = Opcodes.GETFIELD))
	private static int mmetal$scaleRenderWidth(WindowRenderState state) {
		float scale = MetalPipeline.currentScaleFactor();
		return scale >= 0.999f ? state.width : Math.max(1, (int) (state.width * scale));
	}

	@Redirect(method = "render",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/WindowRenderState;height:I",
			opcode = Opcodes.GETFIELD))
	private static int mmetal$scaleRenderHeight(WindowRenderState state) {
		float scale = MetalPipeline.currentScaleFactor();
		return scale >= 0.999f ? state.height : Math.max(1, (int) (state.height * scale));
	}
}