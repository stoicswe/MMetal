package dev.mmetal.metal.mixin;

import com.mojang.renderpearl.backend.opengl.GlSurface;
import dev.mmetal.metal.render.MetalPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The last stop of every rendered frame: {@link GlSurface#present()} is what actually
 * swaps the SDL window. MMetal injects at its head, runs the Metal pipeline (capture
 * &rarr; MetalFX &rarr; effects &rarr; frame interpolation), draws the result to the
 * default framebuffer, performs the swap(s) itself and cancels the vanilla body.
 */
@Mixin(value = GlSurface.class, remap = false)
public abstract class GlSurfaceMixin {

	@Shadow
	private long windowHandle;

	@Inject(method = "present()V", at = @At("HEAD"), cancellable = true)
	private void mmetal$present(CallbackInfo ci) {
		MetalPipeline pipeline = MetalPipeline.instance();
		if (pipeline == null || pipeline.isDisabled()) {
			return;
		}
		MetalPipeline.onPresent(this.windowHandle, ci);
	}
}