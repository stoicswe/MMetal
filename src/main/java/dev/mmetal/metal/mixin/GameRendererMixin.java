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
 * <p>The render scale is applied ONLY to the {@code WindowRenderState.width/height}
 * reads inside {@code GameRenderer.render()} (the size comparison and the
 * {@code resize(width, height)} call), so the main target becomes the scaled size.
 * {@code WindowRenderState} itself stays NATIVE, which keeps all GUI math in vanilla
 * coordinates:</p>
 * <ul>
 *   <li>the GUI ortho projection extent ({@code width / guiScale}) stays native, so
 *       the HUD keeps vanilla proportions and is simply rasterized at the reduced
 *       resolution (then upscaled with the world as one image),</li>
 *   <li>the scissor rects are computed in native pixels and scaled to the target by
 *       {@code GuiRendererMixin},</li>
 *   <li>the GUI layout and mouse mapping are untouched.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Redirect(method = "render",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/WindowRenderState;width:I",
			opcode = Opcodes.GETFIELD))
	private static int mmetal$scaledWidth(WindowRenderState state) {
		float scale = MetalPipeline.currentScaleFactor();
		return scale >= 0.999f ? state.width : Math.max(1, Math.round(state.width * scale));
	}

	@Redirect(method = "render",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/WindowRenderState;height:I",
			opcode = Opcodes.GETFIELD))
	private static int mmetal$scaledHeight(WindowRenderState state) {
		float scale = MetalPipeline.currentScaleFactor();
		return scale >= 0.999f ? state.height : Math.max(1, Math.round(state.height * scale));
	}
}
