package dev.mmetal.metal.mixin;

import dev.mmetal.metal.render.MetalStats;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Appends the MMetal Metal metrics to the F3 overlay, Metal-HUD style.
 *
 * <p>{@code extractLines} draws the strings it is given, so the lines must be inserted
 * at HEAD. The overlay reuses its line lists between frames, so any previous MMetal
 * lines are replaced rather than appended (idempotent, exactly one column per frame).</p>
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
	@Unique
	private static long mmetal$lastFrame = Long.MIN_VALUE;

	@Inject(method = "extractLines", at = @At("HEAD"))
	private void mmetal$appendMetalMetrics(GuiGraphicsExtractor extractor, List<String> lines,
	                                       boolean flag, int value, CallbackInfo ci) {
		if (!MetalStats.supported()) {
			return;
		}
		long frame = MetalStats.frames;
		if (mmetal$lastFrame == frame) {
			return;
		}
		mmetal$lastFrame = frame;
		lines.removeIf(line -> line.startsWith("[MMetal]"));
		lines.addAll(MetalStats.lines());
	}
}